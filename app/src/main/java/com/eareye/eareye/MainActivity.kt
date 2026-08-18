package com.eareye.eareye

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton // Import ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.speech.tts.TextToSpeech
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.eareye.eareye.Constants.LABELS_PATH
import com.eareye.eareye.Constants.MODEL_PATH
import com.eareye.eareye.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var tts: TextToSpeech? = null
    private var freeTTS: FreeTTS? = null
    private var lastSpeakTime: Long = 0
    private val SPEAK_DELAY_MS = 7000
    private val DISTANCE_THRESHOLD = 0.05f
    private val CONFIDENCE_THRESHOLD = 0.7f
    private val CRITICAL_DISTANCE_THRESHOLD = 0.35f
    private val VERY_CRITICAL_DISTANCE_THRESHOLD = 0.5f
    private val lastSpokenObjectIds = mutableMapOf<Int, Long>()
    private val OBJECT_SPEAK_DELAY_MS = 15000

    private val trackedObjects = mutableMapOf<Int, TrackedObject>()
    private var nextObjectId = 0
    private var currentFrameNumber: Long = 0
    private val MAX_INVISIBLE_FRAMES = 10
    private val MIN_IOU_THRESHOLD = 0.3f

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var mainMenuLayout: ConstraintLayout
    private lateinit var cameraViewLayout: ConstraintLayout
    private lateinit var buttonOpenCamera: ImageButton // Change type here
    private lateinit var buttonCallEmergencyContact: Button
    private lateinit var buttonSendLocation: Button
    private lateinit var buttonCall112: Button

    private lateinit var pickContactLauncher: ActivityResultLauncher<Intent>
    private var selectedEmergencyContactNumber: String? = null
    private val PREFS_NAME = "EmergencyContactPrefs"
    private val PREF_CONTACT_NUMBER = "emergencyContactNumber"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mainMenuLayout = binding.mainMenuLayout
        cameraViewLayout = binding.cameraViewLayout
        buttonOpenCamera = binding.buttonOpenCamera
        buttonCallEmergencyContact = binding.buttonCallEmergencyContact
        buttonSendLocation = binding.buttonSendLocation
        buttonCall112 = binding.buttonCall112

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        }

        tts = TextToSpeech(this, this)
        freeTTS = FreeTTS(this)
        coroutineScope.launch {
            freeTTS?.initialize()
        }

        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        loadSavedEmergencyContact()
        setupContactPicker()
        bindListeners()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "Android TTS initialization successful.")
            val result = tts?.setLanguage(Locale("tr", "TR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Specified language (tr_TR) is not supported or missing data.")
            } else {
                Log.d(TAG, "TTS language set to tr_TR successfully.")
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
            }
        } else {
            Log.e(TAG, "TTS initialization failed. Status code: $status")
        }
    }

    private fun bindListeners() {
        buttonOpenCamera.setOnClickListener {
            if (allPermissionsGranted()) {
                showCameraView()
                startCamera()
            } else {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
            }
        }

        buttonCallEmergencyContact.setOnClickListener {
            if (selectedEmergencyContactNumber != null) {
                if (hasCallPhonePermission()) {
                    makeCall(selectedEmergencyContactNumber!!)
                    Toast.makeText(this, getString(R.string.calling_saved_contact, selectedEmergencyContactNumber), Toast.LENGTH_SHORT).show()
                } else {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
                }
            } else {
                if (hasReadContactsPermission()) {
                    pickContactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                } else {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    Toast.makeText(this, getString(R.string.read_contacts_permission_required), Toast.LENGTH_LONG).show()
                }
            }
        }

        buttonCallEmergencyContact.setOnLongClickListener {
            selectedEmergencyContactNumber = null
            clearSavedEmergencyContact()
            Toast.makeText(this, getString(R.string.no_emergency_contact_saved) + " Please pick a new one.", Toast.LENGTH_SHORT).show()
            true
        }

        buttonSendLocation.setOnClickListener {
            if (selectedEmergencyContactNumber == null) {
                Toast.makeText(this, getString(R.string.no_emergency_contact_saved) + " Please select a contact using the 'Call Contact' button first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (hasSendSmsPermission() && hasLocationPermissions()) {
                sendSmsWithLocation(selectedEmergencyContactNumber!!)
            } else {
                val permissionsToRequest = mutableListOf<String>()
                if (!hasSendSmsPermission()) permissionsToRequest.add(Manifest.permission.SEND_SMS)
                if (!hasLocationPermissions()) {
                    permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (permissionsToRequest.isNotEmpty()) {
                    requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }
        }

        buttonCall112.setOnClickListener {
            if (hasCallPhonePermission()) {
                makeCall("112")
            } else {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            }
        }

        binding.isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
            cameraExecutor.submit {
                detector?.restart(isGpu = isChecked)
            }
            if (isChecked) {
                buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
            } else {
                buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
            }
        }
    }

    private fun showMainMenu() {
        mainMenuLayout.visibility = View.VISIBLE
        cameraViewLayout.visibility = View.GONE
        stopCamera()
    }

    private fun showCameraView() {
        mainMenuLayout.visibility = View.GONE
        cameraViewLayout.visibility = View.VISIBLE
    }

    private fun startCamera() {
        if (cameraProvider == null) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this))
        } else {
            bindCameraUseCases()
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: run {
            Log.e(TAG, "Camera initialization failed.")
            Toast.makeText(this, getString(R.string.camera_init_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build()
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            currentFrameNumber++
            detector?.detect(rotatedBitmap)
        }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, getString(R.string.camera_bind_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCallPhonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    private fun hasSendSmsPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    private fun hasLocationPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasReadContactsPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun setupContactPicker() {
        pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val contactUri: Uri? = result.data?.data
                if (contactUri != null) {
                    contentResolver.query(contactUri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            if (numberIndex >= 0) {
                                val number = cursor.getString(numberIndex)
                                selectedEmergencyContactNumber = number
                                saveEmergencyContact(number)
                                Toast.makeText(this, getString(R.string.emergency_contact_saved, number), Toast.LENGTH_LONG).show()
                                if (hasCallPhonePermission()) {
                                    makeCall(number)
                                } else {
                                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
                                    Toast.makeText(this, getString(R.string.call_permission_required_to_call) + " Contact saved. Try calling again.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(this, getString(R.string.could_not_retrieve_phone_number), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.contact_selection_cancelled), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult: Map<String, Boolean> ->
        val grantedPermissions = permissionsResult.filterValues { it }.keys
        var allRequestedPermissionsGrantedInThisBatch = true

        permissionsResult.forEach { (permission, isGranted) ->
            Log.d(TAG, "Permission $permission = $isGranted")
            if (!isGranted) {
                allRequestedPermissionsGrantedInThisBatch = false
            }
        }

        if (allRequestedPermissionsGrantedInThisBatch) {
            if (permissionsResult.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.all_permissions_granted), Toast.LENGTH_SHORT).show()
            }
            // Attempt action if relevant permissions were just granted
            if (grantedPermissions.contains(Manifest.permission.SEND_SMS) && hasLocationPermissions()) {
                if (selectedEmergencyContactNumber != null) {
                    Toast.makeText(this, getString(R.string.sms_location_permissions_granted_sending), Toast.LENGTH_SHORT).show()
                    sendSmsWithLocation(selectedEmergencyContactNumber!!)
                } else {
                    Toast.makeText(this, getString(R.string.no_emergency_contact_saved) + " Please select a contact first for sending SMS.", Toast.LENGTH_LONG).show()
                }
            } else if (grantedPermissions.contains(Manifest.permission.CALL_PHONE)) {
                Toast.makeText(this, getString(R.string.call_permission_required_to_call) + " Permission granted. Try action again.", Toast.LENGTH_LONG).show()
            } else if (grantedPermissions.contains(Manifest.permission.READ_CONTACTS)) {
                Toast.makeText(this, "Read contacts permission granted. Try picking a contact again.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.permissions_denied_limited_functionality), Toast.LENGTH_LONG).show()
            checkAndShowSettingsDialog(permissionsResult)
        }
    }

    private fun checkAndShowSettingsDialog(permissionsResult: Map<String, Boolean>) {
        var shouldShowSettingsLink = false
        permissionsResult.entries.forEach { entry ->
            if (!entry.value && !ActivityCompat.shouldShowRequestPermissionRationale(this, entry.key)) {
                shouldShowSettingsLink = true
            }
        }
        if (shouldShowSettingsLink) {
            showSettingsDialog()
        }
    }

    private fun makeCall(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to make call due to security exception: ${e.message}")
            Toast.makeText(this, getString(R.string.failed_to_make_call_check_permissions), Toast.LENGTH_LONG).show()
        }
    }

    private fun sendSmsWithLocation(phoneNumber: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.location_permission_not_granted_sms), Toast.LENGTH_LONG).show()
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Toast.makeText(this, getString(R.string.location_services_disabled_message), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val message = "Emergency! My current location is: http://maps.google.com/maps?q=${location.latitude},${location.longitude}"
                val sanitizedPhoneNumber = phoneNumber.filter { it.isDigit() } // Sanitize the number

                Log.d(TAG, "Attempting to send SMS to: '$sanitizedPhoneNumber' with message: '$message'")

                try {
                    // val smsManager = getSystemService(SmsManager::class.java) // Alternative way
                    @Suppress("DEPRECATION") // getDefault() is deprecated but widely used and reliable
                    val smsManager = SmsManager.getDefault()

                    if (sanitizedPhoneNumber.isEmpty()) {
                        Log.e(TAG, "Sanitized phone number is empty.")
                        Toast.makeText(this, "Invalid phone number.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }
                    if (message.isEmpty()) {
                        Log.e(TAG, "SMS message is empty.")
                        Toast.makeText(this, "Cannot send empty message.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    smsManager.sendTextMessage(sanitizedPhoneNumber, null, message, null, null)
                    Log.d(TAG, "SMS sendTextMessage called for $sanitizedPhoneNumber")
                    Toast.makeText(this, getString(R.string.location_sent_to, sanitizedPhoneNumber), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "SMS sending failed for $sanitizedPhoneNumber: ${e.message}", e) // Log the full exception
                    Toast.makeText(this, getString(R.string.failed_to_send_sms), Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "Could not get current location to send SMS.")
                Toast.makeText(this, getString(R.string.could_not_get_location_sms), Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get location: ${e.message}")
            Toast.makeText(this, getString(R.string.failed_to_get_location), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettingsDialog() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
        Toast.makeText(this, getString(R.string.please_grant_permissions_in_settings), Toast.LENGTH_LONG).show()
    }

    private fun saveEmergencyContact(number: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(PREF_CONTACT_NUMBER, number).apply()
    }

    private fun loadSavedEmergencyContact() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedEmergencyContactNumber = prefs.getString(PREF_CONTACT_NUMBER, null)
    }

    private fun clearSavedEmergencyContact() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().remove(PREF_CONTACT_NUMBER).apply()
        selectedEmergencyContactNumber = null
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        freeTTS?.shutdown()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (cameraViewLayout.visibility == View.VISIBLE && allPermissionsGranted()) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (cameraViewLayout.visibility == View.VISIBLE) {
            stopCamera()
        }
    }

    @Deprecated("Use onBackPressedDispatcher instead", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    override fun onBackPressed() {
        if (cameraViewLayout.visibility == View.VISIBLE) {
            showMainMenu()
        } else {
            super.onBackPressed()
        }
    }

    private fun trackObjects(boundingBoxes: List<BoundingBox>): List<TrackedObject> {
        trackedObjects.values.forEach { it.predict() }
        val matchedBoxIndices = mutableSetOf<Int>()
        val matchedObjectIds = mutableSetOf<Int>()
        boundingBoxes.forEachIndexed { boxIndex, box ->
            var bestIoU = MIN_IOU_THRESHOLD
            var bestObjectId = -1
            trackedObjects.forEach { (objectId, trackedObject) ->
                if (box.cls == trackedObject.box.cls) {
                    val iou = TrackedObject.calculateIoU(box, trackedObject.box)
                    if (iou > bestIoU) {
                        bestIoU = iou
                        bestObjectId = objectId
                    }
                }
            }
            if (bestObjectId != -1) {
                trackedObjects[bestObjectId]?.update(box, currentFrameNumber)
                matchedBoxIndices.add(boxIndex)
                matchedObjectIds.add(bestObjectId)
            }
        }
        boundingBoxes.forEachIndexed { boxIndex, box ->
            if (boxIndex !in matchedBoxIndices) {
                val newObjectId = nextObjectId++
                val kalmanState = KalmanState(x = box.cx, y = box.cy, width = box.w, height = box.h)
                trackedObjects[newObjectId] = TrackedObject(id = newObjectId, box = box, lastSeenFrame = currentFrameNumber, firstSeenFrame = currentFrameNumber, kalmanState = kalmanState)
            }
        }
        val objectsToRemove = mutableListOf<Int>()
        trackedObjects.forEach { (objectId, trackedObject) ->
            if (trackedObject.consecutiveInvisibleCount > MAX_INVISIBLE_FRAMES) {
                objectsToRemove.add(objectId)
            }
        }
        objectsToRemove.forEach { trackedObjects.remove(it) }
        return trackedObjects.values.toList()
    }

    private fun provideFeedback(trackedObjects: List<TrackedObject>) {
        if (trackedObjects.isEmpty()) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpeakTime <= SPEAK_DELAY_MS) {
            Log.d(TAG, "Konuşma gecikmesi devam ediyor.")
            return
        }
        val highConfidenceObjects = trackedObjects.filter { it.box.cnf >= CONFIDENCE_THRESHOLD }
        if (highConfidenceObjects.isEmpty()) {
            Log.d(TAG, "Yeterince güvenilir nesne bulunamadı.")
            return
        }
        val veryCriticalObjects = highConfidenceObjects.filter {
            it.box.h >= VERY_CRITICAL_DISTANCE_THRESHOLD &&
                    (currentTime - (lastSpokenObjectIds[it.id] ?: 0) > OBJECT_SPEAK_DELAY_MS / 3)
        }
        if (veryCriticalObjects.isNotEmpty()) {
            val closestVeryCritical = veryCriticalObjects.maxByOrNull { it.box.h }
            if (closestVeryCritical != null) {
                val feedbackText = "ACİL DİKKAT! ${closestVeryCritical.getPositionDescription()} çok yakında bir ${closestVeryCritical.box.clsName} var!"
                speakFeedback(feedbackText, true)
                lastSpokenObjectIds[closestVeryCritical.id] = currentTime
                return
            }
        }
        val criticalObjects = highConfidenceObjects.filter {
            it.box.h >= CRITICAL_DISTANCE_THRESHOLD && it.box.h < VERY_CRITICAL_DISTANCE_THRESHOLD &&
                    (currentTime - (lastSpokenObjectIds[it.id] ?: 0) > OBJECT_SPEAK_DELAY_MS / 2)
        }
        if (criticalObjects.isNotEmpty()) {
            val closestCritical = criticalObjects.maxByOrNull { it.box.h }
            if (closestCritical != null) {
                val feedbackText = "DİKKAT! ${closestCritical.getPositionDescription()} yakında bir ${closestCritical.box.clsName} var!"
                speakFeedback(feedbackText, true)
                lastSpokenObjectIds[closestCritical.id] = currentTime
                return
            }
        }
        val approachingObjects = highConfidenceObjects.filter {
            it.isMoving && it.movementDirection == "yaklaşıyor" &&
                    (currentTime - (lastSpokenObjectIds[it.id] ?: 0) > OBJECT_SPEAK_DELAY_MS)
        }
        if (approachingObjects.isNotEmpty()) {
            val closestApproaching = approachingObjects.minByOrNull {
                val distance = when (it.getDistanceDescription()) {
                    "yakında" -> 0
                    "orta mesafede" -> 1
                    else -> 2
                }
                distance
            }
            if (closestApproaching != null) {
                speakFeedback(closestApproaching.generateFeedbackText(), true)
                lastSpokenObjectIds[closestApproaching.id] = currentTime
                return
            }
        }
        val movingObjects = highConfidenceObjects.filter {
            it.isMoving &&
                    (currentTime - (lastSpokenObjectIds[it.id] ?: 0) > OBJECT_SPEAK_DELAY_MS)
        }
        if (movingObjects.isNotEmpty()) {
            val closestMoving = movingObjects.minByOrNull {
                val distance = when (it.getDistanceDescription()) {
                    "yakında" -> 0
                    "orta mesafede" -> 1
                    else -> 2
                }
                distance
            }
            if (closestMoving != null) {
                speakFeedback(closestMoving.generateFeedbackText(), false)
                lastSpokenObjectIds[closestMoving.id] = currentTime
                return
            }
        }
        val nearbyStationaryObjects = highConfidenceObjects.filter {
            it.getDistanceDescription() == "yakında" &&
                    !it.isMoving &&
                    (currentTime - (lastSpokenObjectIds[it.id] ?: 0) > OBJECT_SPEAK_DELAY_MS)
        }
        if (nearbyStationaryObjects.isNotEmpty()) {
            val closestStationary = nearbyStationaryObjects.maxByOrNull { it.box.h }
            if (closestStationary != null) {
                speakFeedback(closestStationary.generateFeedbackText(), false)
                lastSpokenObjectIds[closestStationary.id] = currentTime
            }
        }
    }

    private fun speakFeedback(text: String, isUrgent: Boolean) {
        Log.d(TAG, "Sesli geri bildirim: $text (Acil: $isUrgent)")
        coroutineScope.launch {
            val success = freeTTS?.speak(text, isUrgent) ?: false
            if (!success) {
                withContext(Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
            lastSpeakTime = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        // private const val EMERGENCY_CONTACT_NUMBER = "1234567890" // No longer used as primary for SMS
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private val ALL_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS
        )
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            val trackedObjects = trackObjects(boundingBoxes)
            val trackedBoxes = trackedObjects.map { it.box }
            binding.overlay.apply {
                setResults(trackedBoxes)
                invalidate()
            }
            provideFeedback(trackedObjects)
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Detector Error: $error")
        }
    }
}
