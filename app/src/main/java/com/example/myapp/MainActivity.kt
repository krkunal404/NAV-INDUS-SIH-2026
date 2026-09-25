package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.myapp.llm.ActionExecutor
import com.example.myapp.llm.NimoAction
import com.example.myapp.llm.NimoLLM
import com.example.myapp.overlay.ActiveVoiceInput
import com.example.myapp.overlay.OverlayController
import com.example.myapp.overlay.OverlayService
import com.example.myapp.overlay.WakeWordManager
import com.example.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        if (micGranted) {
            setupNimo()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fired when the mic icon inside the overlay card is tapped
        OverlayService.onMicTapped = {
            Log.d("Nimo", "Mic tapped — starting active voice capture")
            OverlayService.updateStatus("Listening for query...")

            // Pause wake word manager so mic resource isn't locked
            WakeWordManager.stop()

            ActiveVoiceInput.startListening(
                context = this,
                onResult = { spokenText ->
                    Log.d("Nimo", "Voice captured: $spokenText")
                    OverlayService.updateStatus("Voice: \"$spokenText\"")
                    OverlayService.onQuerySubmitted?.invoke(spokenText)
                    WakeWordManager.start(this) {
                        runOnUiThread { OverlayController.show(this) }
                    }
                },
                onError = { err ->
                    Log.e("Nimo", "Voice capture error: $err")
                    OverlayService.updateStatus("Listening ended")
                    WakeWordManager.start(this) {
                        runOnUiThread { OverlayController.show(this) }
                    }
                }
            )
        }

        // Fired when the user types or speaks a query
        OverlayService.onQuerySubmitted = { text ->
            Log.d("Nimo", "Query submitted: $text")
            OverlayService.updateStatus("Thinking...")

            NimoLLM.ask(text, object : NimoLLM.Callback {
                override fun onResult(action: NimoAction) {
                    runOnUiThread {
                        OverlayService.updateStatus(action.reply)
                        Toast.makeText(this@MainActivity, action.reply, Toast.LENGTH_LONG).show()
                        ActionExecutor.execute(this@MainActivity, action)
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        OverlayService.updateStatus("Error: $message")
                        Toast.makeText(this@MainActivity, "Error: $message", Toast.LENGTH_SHORT).show()
                    }
                }
            })
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
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }

        startNimo()
    }

    private fun startNimo() {
        if (!OverlayController.hasPermission(this)) {
            // Opens system settings; user must manually enable "draw over other apps"
            OverlayController.requestPermission(this)
            return
        }

        // Always-listening wake word detection — says "Ok Nimo" / "Hello Nimo"
        // to bring up the overlay automatically.
        WakeWordManager.start(this) {
            runOnUiThread {
                OverlayController.show(this)
            }
        }

        OverlayController.show(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveVoiceInput.destroy()
        WakeWordManager.stop()
    }
}
