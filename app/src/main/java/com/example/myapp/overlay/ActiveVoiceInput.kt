package com.example.myapp.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

object ActiveVoiceInput {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startListening(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val appContext = context.applicationContext
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                onError("Speech recognition not available")
                return@post
            }

            destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        onResult(text)
                    } else {
                        onError("No speech recognized")
                    }
                    destroy()
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        else -> "Listening ended ($error)"
                    }
                    onError(message)
                    destroy()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        }
    }

    fun destroy() {
        mainHandler.post {
            runCatching {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            }
            speechRecognizer = null
        }
    }
}
