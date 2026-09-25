package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.myapp.overlay.OverlayController
import com.example.myapp.overlay.OverlayService
import com.example.myapp.overlay.WakeWordManager
import com.example.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startNimo()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fired when the mic icon inside the overlay card is tapped
        OverlayService.onMicTapped = {
            Log.d("Nimo", "Mic tapped — start active voice capture here")
            Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
            // TODO: hook up your actual speech-to-text capture for a live query here
        }

        // Fired when the user types a query into the overlay's input field
        OverlayService.onQuerySubmitted = { text ->
            Log.d("Nimo", "Query submitted: $text")
            // TODO: send `text` to your LLM and show the response
        }

        setupNimo()

        setContent {
            MyAppTheme {
            }
        }
    }

    private fun setupNimo() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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

        // Optional: also show the overlay immediately on launch, useful while
        // testing the UI without needing to say the wake word every time.
        // Comment this out once wake word detection is confirmed working.
        OverlayController.show(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        WakeWordManager.stop()
    }
}