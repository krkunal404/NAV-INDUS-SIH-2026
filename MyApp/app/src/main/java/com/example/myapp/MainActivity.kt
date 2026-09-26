package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.myapp.llm.ActionExecutor
import com.example.myapp.llm.CallManager
import com.example.myapp.llm.NimoAction
import com.example.myapp.llm.NimoLLM
import com.example.myapp.overlay.ActiveVoiceInput
import com.example.myapp.overlay.OverlayController
import com.example.myapp.overlay.OverlayService
import com.example.myapp.overlay.WakeWordManager
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager
import com.example.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        if (micGranted) {
            setupNimo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Language and Text-To-Speech
        LanguageManager.init(this)
        TTSManager.init(this)

        // Fired when the mic button inside overlay or activity is tapped
        OverlayService.onMicTapped = {
            Log.d("Nimo", "Mic tapped — starting active voice capture")
            TTSManager.stop()
            if (!CallManager.isAwaitingConfirmation) {
                OverlayService.updateStatus("Listening... speak now")
            }

            WakeWordManager.stop()

            ActiveVoiceInput.startListening(
                context = this,
                onPartial = { partialText ->
                    runOnUiThread {
                        OverlayService.updateStatus("Listening: \"$partialText\"")
                    }
                },
                onResult = { spokenText ->
                    Log.d("Nimo", "Voice captured: $spokenText")
                    runOnUiThread {
                        OverlayService.updateStatus("Voice: \"$spokenText\"")
                        OverlayService.onQuerySubmitted?.invoke(spokenText)
                    }
                },
                onError = { err ->
                    Log.e("Nimo", "Voice capture error: $err")
                    runOnUiThread {
                        OverlayService.updateStatus("Voice ended ($err)")
                        CallManager.cancelPending()
                        restartWakeWord()
                    }
                }
            )
        }

        // Fired when a query is submitted (voice or typed)
        OverlayService.onQuerySubmitted = { text ->
            Log.d("Nimo", "Query submitted: $text")

            // Check if we are waiting for confirmation for placing a call
            if (CallManager.isAwaitingConfirmation) {
                val handled = CallManager.handleUserConfirmation(this@MainActivity, text)
                if (handled) {
                    restartWakeWord()
                }
            } else {
                OverlayService.updateStatus("Thinking...")

                NimoLLM.ask(this@MainActivity, text, object : NimoLLM.Callback {
                    override fun onResult(action: NimoAction) {
                        runOnUiThread {
                            if (action !is NimoAction.CallContact) {
                                OverlayService.updateStatus(action.reply)
                                TTSManager.speak(action.reply)
                            }
                            ActionExecutor.execute(this@MainActivity, action)
                            if (action !is NimoAction.CallContact) {
                                restartWakeWord()
                            }
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            OverlayService.updateStatus("Error: $message")
                            val errMsg = if (LanguageManager.isHindi()) "क्षमा करें, कोई त्रुटि हुई।" else "Sorry, I encountered an error"
                            TTSManager.speak(errMsg)
                            restartWakeWord()
                        }
                    }
                })
            }
        }

        setContent {
            MyAppTheme {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupNimo()
    }

    private fun setupNimo() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }

        startNimo()
    }

    private fun startNimo() {
        if (!OverlayController.hasPermission(this)) {
            OverlayController.requestPermission(this)
            return
        }

        OverlayController.show(this)
        restartWakeWord()
    }

    private fun restartWakeWord() {
        WakeWordManager.start(this) {
            runOnUiThread {
                Log.d("Nimo", "Wake word detected!")
                val greeting = if (LanguageManager.isHindi()) {
                    "नमस्ते! मैं निमो हूँ, आपकी क्या सहायता कर सकता हूँ?"
                } else {
                    "Hello Nimo! How can I help you?"
                }
                OverlayService.updateStatus(greeting)
                OverlayController.show(this)

                TTSManager.speak(greeting) {
                    runOnUiThread {
                        OverlayService.onMicTapped?.invoke()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveVoiceInput.destroy()
        WakeWordManager.stop()
        TTSManager.shutdown()
        CallManager.cancelPending()
    }
}
