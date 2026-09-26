package com.example.myapp.llm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.example.myapp.overlay.OverlayService
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager

object AlarmManager {

    private const val TAG = "AlarmManager"

    var isAwaitingTimeInput: Boolean = false
        private set

    fun setAlarm(context: Context, hour: Int, minute: Int, message: String = "Nimo Alarm", isTomorrow: Boolean = false) {
        val isHindi = LanguageManager.isHindi()
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        val formattedMin = String.format("%02d", minute)

        val reply = if (isHindi) {
            val dayStr = if (isTomorrow) "कल " else ""
            "${dayStr}$displayHour:$formattedMin $amPm का अलार्म सेट कर दिया गया है।"
        } else {
            val dayStr = if (isTomorrow) " tomorrow" else ""
            "Alarm set for $displayHour:$formattedMin $amPm$dayStr."
        }

        OverlayService.updateStatus(reply)
        TTSManager.speak(reply)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch set alarm intent: ${e.message}")
        }
    }

    fun promptForAlarmTime(context: Context, onPromptUser: () -> Unit) {
        isAwaitingTimeInput = true
        val prompt = if (LanguageManager.isHindi()) {
            "किस समय का अलार्म लगाना है?"
        } else {
            "What time should I set the alarm for?"
        }

        OverlayService.updateStatus(prompt)
        TTSManager.speak(prompt) {
            onPromptUser()
        }
    }

    fun handleTimeInput(context: Context, userSpeech: String): Boolean {
        if (!isAwaitingTimeInput) return false

        val lower = userSpeech.lowercase().trim()

        if (lower.contains("cancel") || lower.contains("stop") || lower.contains("no") || lower.contains("radd") || lower.contains("नहीं") || lower.contains("रद्द")) {
            isAwaitingTimeInput = false
            val cancelMsg = if (LanguageManager.isHindi()) "अलार्म रद्द कर दिया गया।" else "Alarm creation canceled."
            OverlayService.updateStatus(cancelMsg)
            TTSManager.speak(cancelMsg)
            return true
        }

        val parsedTime = parseTimeFromSpeech(userSpeech)
        if (parsedTime != null) {
            isAwaitingTimeInput = false
            val (hour, minute, isTomorrow) = parsedTime
            setAlarm(context, hour, minute, isTomorrow = isTomorrow)
            return true
        }

        val retryMsg = if (LanguageManager.isHindi()) {
            "कृपया समय बताएं, जैसे दोपहर 3:30 बजे या सुबह 7 बजे।"
        } else {
            "Please specify a time, like 3:30 PM or 7 AM."
        }
        OverlayService.updateStatus(retryMsg)
        TTSManager.speak(retryMsg)
        return true
    }

    fun cancelAllAlarms(context: Context) {
        isAwaitingTimeInput = false
        val reply = if (LanguageManager.isHindi()) {
            "आपके लिए अलार्म ऐप खोल रहा हूँ।"
        } else {
            "Opening Alarms for you to manage or cancel alarms."
        }

        OverlayService.updateStatus(reply)
        TTSManager.speak(reply)

        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open clock app: ${e.message}")
        }
    }

    fun cancelPending() {
        isAwaitingTimeInput = false
    }

    private fun normalizeHindiDigits(input: String): String {
        val hindiDigits = charArrayOf('०', '१', '२', '३', '४', '५', '६', '७', '८', '९')
        var result = input
        for (i in hindiDigits.indices) {
            result = result.replace(hindiDigits[i], ('0' + i))
        }
        return result
    }

    fun parseTimeFromSpeech(text: String): Triple<Int, Int, Boolean>? {
        val normalized = normalizeHindiDigits(text.lowercase().trim())
        val isTomorrow = normalized.contains("tomorrow") || normalized.contains("tom") ||
                normalized.contains("kal") || normalized.contains("कल")

        // Relative time first: "in 20 minutes", "after 2 hours", "20 minute baad".
        parseRelativeTime(normalized)?.let { return it }

        val timeRegex = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|बजे)?".toRegex()
        val allMatches = timeRegex.findAll(normalized).toList()
        if (allMatches.isEmpty()) return null

        // A sentence can contain unrelated numbers ("I have 2 meetings, set an
        // alarm for 3 pm"). Prefer whichever match actually carries an am/pm/
        // बजे indicator - that's the one the user meant as a clock time -
        // instead of blindly taking the first number in the sentence.
        val match = allMatches.firstOrNull { it.groupValues[3].isNotBlank() } ?: allMatches.first()

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val min = match.groupValues[2].toIntOrNull() ?: 0
        val indicator = match.groupValues[3].replace(".", "")

        if (indicator == "pm" && hour < 12) {
            hour += 12
        } else if (indicator == "am" && hour == 12) {
            hour = 0
        } else if ((indicator.isEmpty() || indicator == "बजे") &&
            (normalized.contains("evening") || normalized.contains("night") || normalized.contains("afternoon") ||
                    normalized.contains("shaam") || normalized.contains("raat") || normalized.contains("dopahar") ||
                    normalized.contains("शाम") || normalized.contains("रात") || normalized.contains("दोपहर"))
        ) {
            if (hour < 12) hour += 12
        }

        if (hour in 0..23 && min in 0..59) {
            return Triple(hour, min, isTomorrow)
        }

        return null
    }

    /**
     * Handles relative alarm phrasing like "in 20 minutes", "after an hour",
     * "half an hour", "20 minute baad" so Nimo doesn't ignore context the
     * user already gave and ask "what time?" all over again.
     */
    private fun parseRelativeTime(normalized: String): Triple<Int, Int, Boolean>? {
        val isRelative = normalized.contains(" in ") || normalized.startsWith("in ") ||
                normalized.contains("after") || normalized.contains("baad") ||
                normalized.contains("बाद")
        if (!isRelative) return null

        var minutesFromNow: Int? = null

        if (normalized.contains("half an hour") || normalized.contains("half hour") ||
            normalized.contains("aadha ghanta") || normalized.contains("आधा घंटा")
        ) {
            minutesFromNow = 30
        } else if (normalized.contains("quarter of an hour") || normalized.contains("quarter hour")) {
            minutesFromNow = 15
        } else {
            val hourMatch = "(\\d{1,2})\\s*(hour|hr|ghanta|घंटा)".toRegex().find(normalized)
            val minuteMatch = "(\\d{1,3})\\s*(minute|min|minit)".toRegex().find(normalized)
            val hours = hourMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = minuteMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (hours > 0 || minutes > 0) {
                minutesFromNow = hours * 60 + minutes
            } else if (normalized.contains("an hour") || normalized.contains("ek ghanta") || normalized.contains("एक घंटा")) {
                minutesFromNow = 60
            }
        }

        val total = minutesFromNow ?: return null
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, total)
        return Triple(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), false)
    }
}