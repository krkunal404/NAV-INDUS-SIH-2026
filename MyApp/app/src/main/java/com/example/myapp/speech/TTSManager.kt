package com.example.myapp.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object TTSManager {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    @Volatile
    var isTTSActive: Boolean = false
        private set

    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (tts != null) {
            onReady?.invoke()
            return
        }
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTSManager", "Language US missing or not supported")
                }
                isInitialized = true
                Log.d("TTSManager", "TTS initialized successfully")
                onReady?.invoke()

                pendingText?.let { text ->
                    speak(text, pendingOnDone)
                    pendingText = null
                    pendingOnDone = null
                }
            } else {
                Log.e("TTSManager", "TTS initialization failed with status $status")
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return

        if (!isInitialized) {
            pendingText = text
            pendingOnDone = onDone
            return
        }

        // Set locale to Hindi if text has Devanagari characters or current mode is Hindi
        val containsDevanagari = text.any { it.code in 0x0900..0x097F }
        val targetLocale = if (containsDevanagari || LanguageManager.isHindi()) {
            Locale("hi", "IN")
        } else {
            Locale.US
        }

        runCatching {
            tts?.setLanguage(targetLocale)
        }

        isTTSActive = true

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isTTSActive = true
            }

            override fun onDone(utteranceId: String?) {
                isTTSActive = false
                onDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isTTSActive = false
                onDone?.invoke()
            }
        })

        val utteranceId = "NimoTTS_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        isTTSActive = false
        runCatching { tts?.stop() }
    }

    fun isSpeaking(): Boolean {
        return isTTSActive || runCatching { tts?.isSpeaking == true }.getOrDefault(false)
    }

    fun shutdown() {
        isTTSActive = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isInitialized = false
    }
}
