package com.example.myapp.llm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapp.overlay.OverlayService
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager

object EmergencyManager {

    private const val TAG = "EmergencyManager"
    private const val DEFAULT_EMERGENCY_NUMBER = "112"

    fun triggerEmergencyCall(context: Context) {
        val isHindi = LanguageManager.isHindi()
        val number = getEmergencyNumber(context)

        val reply = if (isHindi) {
            "आपकी सुरक्षा के लिए तुरंत इमरजेंसी कॉल लगाया जा रहा है।"
        } else {
            "Emergency SOS! Placing call immediately."
        }

        OverlayService.updateStatus(reply)
        TTSManager.speak(reply)

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPermission) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch emergency call: ${e.message}")
        }
    }

    private fun getEmergencyNumber(context: Context): String {
        val contact = CallManager.lookupContact(context, "emergency")
            ?: CallManager.lookupContact(context, "sos")
        return contact?.number ?: DEFAULT_EMERGENCY_NUMBER
    }
}
