package com.example.myapp.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wake-word detection using Android's built-in SpeechRecognizer.
 * Free, no account/API key needed — but requires internet (uses Google's
 * cloud recognition under the hood) and works best while the app is
 * in the foreground; Android may restrict it running purely in background.
 */
object WakeWordManager {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private val WAKE_PHRASES = listOf("ok nimo", "hello nimo", "hey nimo")

    fun start(context: Context, onWakeWordDetected: () -> Unit) {
        if (recognizer != null) return // already running

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return // device has no speech recognition service available
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                handleResult(results, onWakeWordDetected)
                restartListening(intent)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleResult(partialResults, onWakeWordDetected)
            }

            override fun onError(error: Int) {
                // Common on timeouts/silence — just restart listening
                restartListening(intent)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        isListening = true
        recognizer?.startListening(intent)
    }

    private fun handleResult(bundle: Bundle?, callback: () -> Unit) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) // partial results use same key
        val text = matches?.firstOrNull()?.lowercase() ?: return
        if (WAKE_PHRASES.any { text.contains(it) }) {
            callback()
        }
    }

    private fun restartListening(intent: Intent) {
        if (!isListening) return
        recognizer?.startListening(intent)
    }

    fun stop() {
        isListening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}