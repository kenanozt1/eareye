package com.eareye.eareye

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Kalman filtresi için basit bir durum sınıfı.
 * Bu sınıf, nesnenin konumunu (x, y) ve hızını (vx, vy) takip eder.
 */
class KalmanState(
    var x: Float,     // x konumu (merkez)
    var y: Float,     // y konumu (merkez)
    var vx: Float = 0f, // x yönündeki hız
    var vy: Float = 0f, // y yönündeki hız
    var width: Float,  // genişlik
    var height: Float, // yükseklik
    var uncertainty: Float = 1f // belirsizlik faktörü
) {
    // Durum tahminini günceller (predict)
    fun predict(dt: Float = 1f) {
        // Konum tahmini: konum + hız * zaman
        x += vx * dt
        y += vy * dt
        
        // Belirsizlik artar (process noise)
        uncertainty += 0.1f * dt
    }
    
    // Ölçüm ile durum güncellemesi (update)
    fun update(measuredX: Float, measuredY: Float, measuredWidth: Float, measuredHeight: Float) {
        // Kalman kazancı: belirsizlik / (belirsizlik + ölçüm gürültüsü)
        val kalmanGain = uncertainty / (uncertainty + 0.1f)
        
        // Durum güncellemesi
        val dx = measuredX - x
        val dy = measuredY - y
        x += kalmanGain * dx
        y += kalmanGain * dy
        
        // Hız güncellemesi (basit yaklaşım)
        vx = 0.8f * vx + 0.2f * dx
        vy = 0.8f * vy + 0.2f * dy
        
        // Boyut güncellemesi (basit yaklaşım)
        width = 0.8f * width + 0.2f * measuredWidth
        height = 0.8f * height + 0.2f * measuredHeight
        
        // Belirsizlik azalır (ölçüm ile güncelleme)
        uncertainty *= (1f - kalmanGain)
    }
    
    // Mevcut durumdan BoundingBox oluşturur
    fun toBoundingBox(originalBox: BoundingBox): BoundingBox {
        // Merkez konumdan köşe koordinatlarını hesapla
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        
        return BoundingBox(
            x1 = x - halfWidth,
            y1 = y - halfHeight,
            x2 = x + halfWidth,
            y2 = y + halfHeight,
            cx = x,
            cy = y,
            w = width,
            h = height,
            cnf = originalBox.cnf,
            cls = originalBox.cls,
            clsName = originalBox.clsName
        )
    }
}

/**
 * Takip edilen bir nesneyi temsil eden sınıf.
 */
data class TrackedObject(
    val id: Int,                      // Benzersiz nesne kimliği
    var box: BoundingBox,             // Son tespit edilen sınırlayıcı kutu
    var lastSeenFrame: Long,          // En son hangi frame'de görüldü
    var firstSeenFrame: Long,         // İlk ne zaman görüldü
    var consecutiveInvisibleCount: Int = 0, // Kaç frame'dir görünmüyor
    var kalmanState: KalmanState,     // Kalman filtresi durumu
    var isMoving: Boolean = false,    // Nesne hareket ediyor mu?
    var movementDirection: String = "durağan", // Hareket yönü (sağa, sola, yaklaşıyor, uzaklaşıyor)
    var lastPositions: MutableList<Pair<Float, Float>> = mutableListOf() // Son konumlar (x, y)
) {
    companion object {
        private const val TAG = "TrackedObject"
        private const val MAX_POSITION_HISTORY = 10 // Konum geçmişi için maksimum kayıt sayısı
        private const val MOVEMENT_THRESHOLD = 0.01f // Hareket algılama eşiği
        
        // IoU (Intersection over Union) hesaplama
        fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
            val x1 = maxOf(box1.x1, box2.x1)
            val y1 = maxOf(box1.y1, box2.y1)
            val x2 = minOf(box1.x2, box2.x2)
            val y2 = minOf(box1.y2, box2.y2)
            
            val intersectionArea = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
            val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
            val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
            
            return intersectionArea / (box1Area + box2Area - intersectionArea)
        }
        
        // İki kutu arasındaki merkez noktaların mesafesini hesaplama
        fun calculateCenterDistance(box1: BoundingBox, box2: BoundingBox): Float {
            val dx = box1.cx - box2.cx
            val dy = box1.cy - box2.cy
            return sqrt(dx * dx + dy * dy)
        }
    }
    
    // Nesneyi yeni bir tespit ile günceller
    fun update(newBox: BoundingBox, currentFrame: Long) {
        // Konum geçmişini güncelle
        lastPositions.add(Pair(box.cx, box.cy))
        if (lastPositions.size > MAX_POSITION_HISTORY) {
            lastPositions.removeAt(0)
        }
        
        // Kalman filtresi ile güncelleme
        kalmanState.update(newBox.cx, newBox.cy, newBox.w, newBox.h)
        
        // Hareket analizi
        analyzeMovement()
        
        // Nesne bilgilerini güncelle
        box = newBox
        lastSeenFrame = currentFrame
        consecutiveInvisibleCount = 0
    }
    
    // Nesneyi tahmin et (görünmediği durumlarda)
    fun predict() {
        kalmanState.predict()
        box = kalmanState.toBoundingBox(box)
        consecutiveInvisibleCount++
        
        // Hareket analizi
        analyzeMovement()
    }
    
    // Nesnenin hareketini analiz eder
    private fun analyzeMovement() {
        if (lastPositions.size < 3) return // Yeterli veri yok
        
        // Son birkaç konumu kullanarak hareket yönünü belirle
        val recentPositions = lastPositions.takeLast(minOf(5, lastPositions.size))
        
        // X ve Y yönündeki toplam değişimi hesapla
        var totalDx = 0f
        var totalDy = 0f
        
        for (i in 1 until recentPositions.size) {
            totalDx += recentPositions[i].first - recentPositions[i-1].first
            totalDy += recentPositions[i].second - recentPositions[i-1].second
        }
        
        // Ortalama değişim
        val avgDx = totalDx / (recentPositions.size - 1)
        val avgDy = totalDy / (recentPositions.size - 1)
        
        // Hareket büyüklüğü
        val movementMagnitude = sqrt(avgDx * avgDx + avgDy * avgDy)
        
        // Hareket edip etmediğini belirle
        isMoving = movementMagnitude > MOVEMENT_THRESHOLD
        
        // Hareket yönünü belirle
        if (!isMoving) {
            movementDirection = "durağan"
        } else {
            // Boyut değişimini kontrol et (yaklaşma/uzaklaşma için)
            val sizeChange = if (lastPositions.size >= 5) {
                val oldWidth = kalmanState.width - 5 * kalmanState.vx
                val currentWidth = kalmanState.width
                (currentWidth - oldWidth) / oldWidth
            } else {
                0f
            }
            
            // Yön belirleme
            when {
                abs(avgDx) > abs(avgDy) * 2 -> {
                    // X yönünde baskın hareket
                    movementDirection = if (avgDx > 0) "sağa" else "sola"
                }
                abs(avgDy) > abs(avgDx) * 2 -> {
                    // Y yönünde baskın hareket
                    movementDirection = if (avgDy > 0) "aşağı" else "yukarı"
                }
                sizeChange > 0.05f -> {
                    // Boyut artıyor - yaklaşıyor
                    movementDirection = "yaklaşıyor"
                }
                sizeChange < -0.05f -> {
                    // Boyut azalıyor - uzaklaşıyor
                    movementDirection = "uzaklaşıyor"
                }
                else -> {
                    // Karışık hareket
                    movementDirection = if (abs(avgDx) > abs(avgDy)) {
                        if (avgDx > 0) "sağa" else "sola"
                    } else {
                        if (avgDy > 0) "aşağı" else "yukarı"
                    }
                }
            }
        }
        
        Log.d(TAG, "Nesne #$id hareket: $movementDirection (dx=$avgDx, dy=$avgDy, mag=$movementMagnitude)")
    }
    
    // Nesnenin konumunu (sol, sağ, orta) belirler
    fun getPositionDescription(): String {
        return when {
            box.cx < 0.33f -> "solunuzda"
            box.cx > 0.66f -> "sağınızda"
            else -> "önünüzde"
        }
    }
    
    // Nesnenin mesafesini tahmin eder
    fun getDistanceDescription(): String {
        // İnsan mesafesine göre düzenlenmiş mesafe tahmini
        // Bounding box yüksekliği kullanılarak
        return when {
            box.h < 0.05f -> "çok uzakta" // 10+ metre
            box.h < 0.1f -> "uzakta"      // 5-10 metre
            box.h < 0.2f -> "orta mesafede" // 2-5 metre
            box.h < 0.35f -> "yakında"    // 1-2 metre
            else -> "çok yakında"         // 0-1 metre
        }
    }
    
    // Nesne için sesli geri bildirim metni oluşturur
    fun generateFeedbackText(): String {
        val position = getPositionDescription()
        val distance = getDistanceDescription()
        
        return when {
            consecutiveInvisibleCount > 0 && consecutiveInvisibleCount <= 5 -> {
                // Kısa süre önce kaybolmuş nesne
                "$position bir ${box.clsName} kayboldu."
            }
            isMoving -> {
                // Hareket eden nesne
                when (movementDirection) {
                    "yaklaşıyor" -> "$position $distance bir ${box.clsName} size yaklaşıyor."
                    "uzaklaşıyor" -> "$position $distance bir ${box.clsName} uzaklaşıyor."
                    "sağa" -> "$position $distance bir ${box.clsName} sağa doğru hareket ediyor."
                    "sola" -> "$position $distance bir ${box.clsName} sola doğru hareket ediyor."
                    else -> "$position $distance bir ${box.clsName} var."
                }
            }
            else -> {
                // Durağan nesne
                "$position $distance bir ${box.clsName} var."
            }
        }
    }
}
