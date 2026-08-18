package com.eareye.eareye

import android.content.Context
import android.util.Log

/**
 * Bu sınıf artık kullanılmıyor.
 * Yerine FreeTTS sınıfı kullanılıyor.
 * Bu dosya, geriye dönük uyumluluk için korunmuştur.
 */
class GoogleTTS(private val context: Context) {
    private val TAG = "GoogleTTS"
    
    init {
        Log.w(TAG, "GoogleTTS sınıfı artık kullanılmıyor. Lütfen FreeTTS sınıfını kullanın.")
    }
    
    suspend fun initialize() {
        Log.w(TAG, "Bu metot artık kullanılmıyor. Lütfen FreeTTS sınıfını kullanın.")
    }
    
    suspend fun speak(text: String, force: Boolean = false): Boolean {
        Log.w(TAG, "Bu metot artık kullanılmıyor. Lütfen FreeTTS sınıfını kullanın.")
        return false
    }
    
    fun stopSpeaking() {
        Log.w(TAG, "Bu metot artık kullanılmıyor. Lütfen FreeTTS sınıfını kullanın.")
    }
    
    fun shutdown() {
        Log.w(TAG, "Bu metot artık kullanılmıyor. Lütfen FreeTTS sınıfını kullanın.")
    }
    
    fun isSpeaking(): Boolean {
        Log.w(TAG, "Bu metot artık kullanılmıyor. Lütfen FreeTTS sınıfını kullanın.")
        return false
    }
}
