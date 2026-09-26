package com.example.myapp.llm

import android.content.Context
import com.example.myapp.overlay.OverlayService
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager

object HealthManager {

    private const val PREFS_HEALTH = "nimo_health_prefs"
    private const val KEY_BP = "latest_bp"
    private const val KEY_SUGAR = "latest_sugar"

    fun logHealthData(context: Context, text: String) {
        val lower = text.lowercase().trim()
        val isHindi = LanguageManager.isHindi()
        val prefs = context.getSharedPreferences(PREFS_HEALTH, Context.MODE_PRIVATE)

        // Parse Blood Pressure
        if (lower.contains("bp") || lower.contains("blood pressure") || lower.contains("बीपी")) {
            val digits = "\\d+".toRegex().findAll(lower).map { it.value }.toList()
            if (digits.size >= 2) {
                val sys = digits[0]
                val dia = digits[1]
                val bpVal = "$sys/$dia mmHg"
                prefs.edit().putString(KEY_BP, bpVal).apply()

                val reply = if (isHindi) {
                    "ब्लड प्रेशर $bpVal रिकॉर्ड कर लिया गया है।"
                } else {
                    "Blood pressure logged as $bpVal."
                }
                OverlayService.updateStatus(reply)
                TTSManager.speak(reply)
                return
            }
        }

        // Parse Sugar Level
        if (lower.contains("sugar") || lower.contains("glucose") || lower.contains("शुगर")) {
            val digit = "\\d+".toRegex().find(lower)?.value
            if (digit != null) {
                val sugarVal = "$digit mg/dL"
                prefs.edit().putString(KEY_SUGAR, sugarVal).apply()

                val reply = if (isHindi) {
                    "शुगर लेवल $sugarVal रिकॉर्ड कर लिया गया है।"
                } else {
                    "Sugar level logged as $sugarVal."
                }
                OverlayService.updateStatus(reply)
                TTSManager.speak(reply)
                return
            }
        }

        // Give Health Summary
        getHealthSummary(context)
    }

    fun getHealthSummary(context: Context) {
        val isHindi = LanguageManager.isHindi()
        val prefs = context.getSharedPreferences(PREFS_HEALTH, Context.MODE_PRIVATE)
        val bp = prefs.getString(KEY_BP, "Not recorded")
        val sugar = prefs.getString(KEY_SUGAR, "Not recorded")

        val reply = if (isHindi) {
            "आपका स्वास्थ्य रिकॉर्ड: बीपी: $bp, शुगर: $sugar"
        } else {
            "Your health summary: Blood Pressure: $bp, Sugar Level: $sugar."
        }

        OverlayService.updateStatus(reply)
        TTSManager.speak(reply)
    }
}
