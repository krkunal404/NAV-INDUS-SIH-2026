package com.example.myapp.overlay

import android.content.Context
import com.example.myapp.speech.VoskVoiceManager

/**
 * Offline voice query capture using Vosk speech model from assets.
 */
object ActiveVoiceInput {

    fun startListening(
        context: Context,
        onPartial: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        VoskVoiceManager.startActiveQueryListening(
            context = context,
            onPartial = onPartial,
            onResult = onResult,
            onError = onError
        )
    }

    fun destroy() {
        VoskVoiceManager.stopListening()
    }
}
