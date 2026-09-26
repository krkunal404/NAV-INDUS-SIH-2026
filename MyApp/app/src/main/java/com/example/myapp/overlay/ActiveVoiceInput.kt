package com.example.myapp.overlay

import android.content.Context
import com.example.myapp.speech.GroqWhisperManager
import com.example.myapp.speech.VoskVoiceManager

/**
 * Voice query capture for active (post-wake-word) queries.
 *
 * Uses Groq's Whisper large-v3-turbo model (cloud) for both English and
 * Hindi/Hinglish recognition, since it is significantly more accurate than
 * Android's native SpeechRecognizer and the small offline Vosk model for
 * both languages, especially for accented or mixed speech.
 *
 * Falls back to the offline Vosk model if there is no network / the Groq
 * request fails outright, so the assistant still works without connectivity.
 */
object ActiveVoiceInput {

    fun startListening(
        context: Context,
        onPartial: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        GroqWhisperManager.startActiveQueryListening(
            context = context,
            onPartial = onPartial,
            onResult = onResult,
            onError = { message ->
                // Fall back to offline Vosk if the cloud call couldn't be
                // made at all (no network, missing key, mic init failure).
                if (isLikelyConnectivityOrSetupIssue(message)) {
                    VoskVoiceManager.startActiveQueryListening(
                        context = context,
                        onPartial = onPartial,
                        onResult = onResult,
                        onError = onError
                    )
                } else {
                    onError(message)
                }
            }
        )
    }

    private fun isLikelyConnectivityOrSetupIssue(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("network") ||
                lower.contains("missing groq api key") ||
                lower.contains("microphone permission") ||
                lower.contains("not supported") ||
                lower.contains("failed to initialize microphone")
    }

    fun destroy() {
        GroqWhisperManager.stopListening()
        VoskVoiceManager.stopListening()
    }
}