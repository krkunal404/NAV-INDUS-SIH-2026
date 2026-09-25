package com.example.myapp.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wake-word detection using Android's built-in SpeechRecognizer.
 * Listens for "ok nimo", "hello nimo", or "hey nimo".
 */
object WakeWordManager {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestartRunnable: Runnable? = null
    private val WAKE_PHRASES = listOf("ok nimo", "hello nimo", "hey nimo")

    fun start(context: Context, onWakeWordDetected: () -> Unit) {
        val appContext = context.applicationContext
        mainHandler.post {
            if (isListening) return@post // already running

            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                return@post
            }

            stop() // Clean up any existing instance first

            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    handleResult(results, onWakeWordDetected)
                    restartListeningWithDelay(intent, 2000L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    handleResult(partialResults, onWakeWordDetected)
                }

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stop()
                        return
                    }
                    // Avoid aggressive busy-looping restart to prevent continuous system chimes/beeps
                    restartListeningWithDelay(intent, 3000L)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            isListening = true
            runCatching {
                recognizer?.startListening(intent)
            }
        }
    }

    private fun handleResult(bundle: Bundle?, callback: () -> Unit) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val found = matches.any { transcript ->
            val lower = transcript.lowercase()
            WAKE_PHRASES.any { lower.contains(it) }
        }
        if (found) {
            callback()
        }
    }

    private fun restartListeningWithDelay(intent: Intent, delayMillis: Long) {
        if (!isListening) return
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isListening) {
                runCatching {
                    recognizer?.cancel()
                    recognizer?.startListening(intent)
                }
            }
        }
        pendingRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    fun stop() {
        isListening = false
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
        mainHandler.post {
            runCatching {
                recognizer?.stopListening()
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
    }
}
