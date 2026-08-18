package com.eareye.eareye

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * Ücretsiz Text-to-Speech çözümlerini kullanarak metni sese dönüştüren sınıf.
 * Bu sınıf, Android'in yerleşik TTS'ini iyileştirir ve gerekirse ücretsiz çevrimiçi
 * TTS API'lerini kullanır.
 */
class FreeTTS(private val context: Context) {
    private val TAG = "FreeTTS"
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isSpeaking = false
    private var lastSpeakTime = 0L
    private val SPEAK_DELAY_MS = 3000 // Konuşma gecikmesi (milisaniye) - Bu değer MainActivity'deki değerden farklı, çünkü bu sınıf içindeki kontrol için
    
    // Ücretsiz TTS API URL'si (VoiceRSS ücretsiz katmanı - günlük sınırlı kullanım)
    // NOT: Bu API anahtarı sınırlı kullanım içindir, kendi anahtarınızı almanız önerilir
    private val FREE_TTS_API_URL = "https://api.voicerss.org/"
    private val FREE_TTS_API_KEY = "9d24c884cd3348b1aa8ce7ad3ac7891a" // Örnek anahtar, gerçek bir uygulama için değiştirin
    
    /**
     * Text-to-Speech motorunu başlatır.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            // Android'in yerleşik TTS'ini başlat
            val initListener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Android TTS başlatma başarılı.")
                    val result = tts?.setLanguage(Locale("tr", "TR")) // Türkçe dilini ayarla
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Belirtilen dil (tr_TR) desteklenmiyor veya veri eksik.")
                    } else {
                        Log.d(TAG, "TTS dil ayarı başarılı: tr_TR")
                        
                        // Ses kalitesini iyileştir
                        tts?.setPitch(1.0f) // Normal ton
                        tts?.setSpeechRate(0.9f) // Biraz daha yavaş hız
                        
                        // Mevcut sesleri kontrol et ve en iyi sesi seç
                        val voices = tts?.voices
                        if (voices != null) {
                            // Türkçe kadın sesini bul
                            val turkishFemaleVoice = voices.find { 
                                it.locale.language == "tr" && it.name.contains("female", ignoreCase = true) 
                            }
                            
                            if (turkishFemaleVoice != null) {
                                tts?.voice = turkishFemaleVoice
                                Log.d(TAG, "Türkçe kadın sesi ayarlandı: ${turkishFemaleVoice.name}")
                            } else {
                                Log.d(TAG, "Türkçe kadın sesi bulunamadı, varsayılan ses kullanılıyor.")
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "TTS başlatma başarısız. Durum kodu: $status")
                }
            }
            
            tts = TextToSpeech(context, initListener)
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS başlatılamadı: ${e.message}", e)
        }
    }
    
    /**
     * Verilen metni sese dönüştürür ve çalar.
     * Öncelikle Android'in yerleşik TTS'ini kullanır. Eğer bu başarısız olursa veya
     * acil bir durum varsa, ücretsiz çevrimiçi TTS API'sini kullanmayı dener.
     * 
     * @param text Seslendirilecek metin
     * @param force Acil durum bildirimi mi? Eğer true ise, konuşma gecikmesi kontrolü yapılmaz
     *              ve mevcut konuşma durdurularak yeni konuşma başlatılır.
     * @return Konuşma başarılı ise true, değilse false
     */
    suspend fun speak(text: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        
        // Eğer zorunlu değilse ve son konuşmadan bu yana yeterli zaman geçmediyse, konuşma
        if (!force && currentTime - lastSpeakTime < SPEAK_DELAY_MS) {
            Log.d(TAG, "FreeTTS: Konuşma gecikmesi devam ediyor.")
            return@withContext false
        }
        
        // Eğer acil durum bildirimi ise veya zaten konuşuyorsa, mevcut konuşmayı durdur
        if (force || isSpeaking) {
            Log.d(TAG, "FreeTTS: ${if (force) "Acil durum bildirimi" else "Zaten konuşuyor"}, mevcut konuşma durduruluyor.")
            stopSpeaking()
        }
        
        try {
            // Android'in yerleşik TTS'i ile konuş
            if (tts != null) {
                val result = withContext(Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
                }
                
                if (result == TextToSpeech.SUCCESS) {
                    isSpeaking = true
                    lastSpeakTime = currentTime
                    Log.d(TAG, "Android TTS ile konuşuluyor: $text")
                    return@withContext true
                }
            }
            
            // Eğer Android TTS başarısız olursa veya acil bir durum varsa, çevrimiçi TTS API'sini dene
            if (force || tts == null) {
                return@withContext speakWithOnlineAPI(text, currentTime, force)
            }
            
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Konuşma hatası: ${e.message}", e)
            return@withContext false
        }
    }
    
    // OkHttp istemcisi
    private val client = OkHttpClient()
    
    /**
     * Ücretsiz çevrimiçi TTS API'sini kullanarak metni sese dönüştürür.
     * 
     * @param text Seslendirilecek metin
     * @param currentTime Mevcut zaman (milisaniye)
     * @param force Acil durum bildirimi mi?
     * @return Konuşma başarılı ise true, değilse false
     */
    private suspend fun speakWithOnlineAPI(text: String, currentTime: Long, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // API URL'sini oluştur
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val apiUrl = "$FREE_TTS_API_URL?key=$FREE_TTS_API_KEY&hl=tr-tr&src=$encodedText&r=0&c=mp3&f=8khz_8bit_mono"
            
            // API'ye istek gönder
            val request = Request.Builder()
                .url(apiUrl)
                .build()
            
            // Yanıtı al
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Ses dosyasını geçici bir dosyaya kaydet
                    val tempFile = File(context.cacheDir, "tts_${UUID.randomUUID()}.mp3")
                    FileOutputStream(tempFile).use { output ->
                        response.body?.byteStream()?.copyTo(output)
                    }
                    
                    // MediaPlayer ile çal
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .build()
                        )
                        setDataSource(tempFile.path)
                        setOnCompletionListener {
                            isSpeaking = false
                            it.release()
                            tempFile.delete()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer hatası: $what, $extra")
                            isSpeaking = false
                            false
                        }
                    prepare()
                    start()
                }
                
                isSpeaking = true
                lastSpeakTime = currentTime
                Log.d(TAG, "Çevrimiçi TTS API ile konuşuluyor: $text (Acil: $force)")
                    
                    return@withContext true
                } else {
                    Log.e(TAG, "API yanıt hatası: ${response.code}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Çevrimiçi TTS hatası: ${e.message}", e)
            return@withContext false
        }
    }
    
    /**
     * Mevcut konuşmayı durdurur.
     */
    fun stopSpeaking() {
        tts?.stop()
        
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
            mediaPlayer = null
        }
        
        isSpeaking = false
    }
    
    /**
     * Kaynakları serbest bırakır.
     */
    fun shutdown() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
    
    /**
     * Konuşma durumunu döndürür.
     */
    fun isSpeaking(): Boolean {
        return isSpeaking || (tts?.isSpeaking ?: false)
    }
}
