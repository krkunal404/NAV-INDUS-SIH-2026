package com.example.myapp.overlay

import android.content.Context
import com.example.myapp.speech.VoskVoiceManager

/**
 * Offline wake-word detection using Vosk speech model from assets.
 * Listens for "hello nimo", "ok nimo", "hey nimo", or "nimo".
 */
object WakeWordManager {

    fun start(context: Context, onWakeWordDetected: (String?) -> Unit) {
        VoskVoiceManager.startWakeWordListening(context, onWakeWordDetected)
    }

    fun stop() {
        VoskVoiceManager.stopListening()
    }
}