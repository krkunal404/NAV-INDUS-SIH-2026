package com.example.myapp.llm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.myapp.overlay.OverlayService
import com.example.myapp.speech.TTSManager

object ActionExecutor {

    private val APP_PACKAGES = mapOf(
        "google" to "com.google.android.googlequicksearchbox",
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "phone" to "com.google.android.dialer",
        "camera" to "com.android.camera2",
        "gallery" to "com.google.android.apps.photos",
        "settings" to "com.android.settings",
        "chrome" to "com.android.chrome"
    )

    // Common mis-hearings / typos for each known app, so garbled speech
    // recognition output (e.g. "gogle", "whtapp", "wh app") still resolves
    // to the right app instead of failing to launch anything.
    private val APP_ALIASES = mapOf(
        "gogle" to "google", "gugal" to "google", "googl" to "google", "guugal" to "google",
        "whtapp" to "whatsapp", "whatap" to "whatsapp", "watsapp" to "whatsapp",
        "wattsapp" to "whatsapp", "whatsup" to "whatsapp", "whats app" to "whatsapp",
        "wh app" to "whatsapp", "whasap" to "whatsapp", "vasap" to "whatsapp",
        "vhatsapp" to "whatsapp", "app whatsapp" to "whatsapp",
        "utub" to "youtube", "youtub" to "youtube", "you tube" to "youtube", "yt" to "youtube",
        "krome" to "chrome", "chorme" to "chrome", "crome" to "chrome",
        "diyaler" to "phone", "dialer" to "phone", "phon" to "phone",
        "seting" to "settings", "setting" to "settings"
    )

    /** Strips everything but lowercase letters so "wh app"/"wh-app" compare like "whapp". */
    private fun normalizeForFuzzyMatch(s: String): String =
        s.lowercase().filter { it.isLetter() }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            for (j in 0..b.length) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    /**
     * Resolves a possibly mis-heard/misspelled app name (e.g. "gogle",
     * "whtapp", "wh app") to one of Nimo's known canonical app keys
     * ("google", "whatsapp", ...), or null if nothing is close enough.
     */
    private fun fuzzyResolveKnownAppKey(rawName: String): String? {
        val cleanName = rawName.lowercase().trim()
        if (cleanName.isBlank()) return null

        APP_PACKAGES[cleanName]?.let { return cleanName }
        APP_ALIASES[cleanName]?.let { return it }

        val normalizedInput = normalizeForFuzzyMatch(cleanName)
        if (normalizedInput.isBlank()) return null

        var bestKey: String? = null
        var bestDistance = Int.MAX_VALUE
        val candidates = APP_PACKAGES.keys + APP_ALIASES.values
        for (candidateKey in candidates.distinct()) {
            val distance = levenshteinDistance(normalizedInput, candidateKey)
            // Allow roughly up to 40% of the word to differ - enough to absorb
            // ASR/typo noise ("whtapp" vs "whatsapp") without matching unrelated words.
            val maxAllowed = maxOf(1, (candidateKey.length * 0.4).toInt())
            if (distance <= maxAllowed && distance < bestDistance) {
                bestDistance = distance
                bestKey = candidateKey
            }
        }
        return bestKey
    }

    /** Best-effort canonical display/package name for an app the user asked to open. */
    fun canonicalAppName(rawName: String): String {
        val key = fuzzyResolveKnownAppKey(rawName)
        return key?.replaceFirstChar { it.uppercase() } ?: rawName
    }

    fun execute(context: Context, action: NimoAction) {
        when (action) {
            is NimoAction.OpenApp -> openApp(context, action.appName)
            is NimoAction.SearchWeb -> searchWeb(context, action.query)
            is NimoAction.CallContact -> callContact(context, action.contactName)
            is NimoAction.EmergencyCall -> {
                EmergencyManager.triggerEmergencyCall(context)
            }
            is NimoAction.ReadMessage -> {
                MessageManager.readMessages(context, action.senderName, action.isWhatsApp)
            }
            is NimoAction.SendMessage -> {
                if (action.isWhatsApp) {
                    MessageManager.sendWhatsAppMessage(context, action.contactName, action.messageText)
                } else {
                    MessageManager.sendSmsMessage(context, action.contactName, action.messageText)
                }
            }
            is NimoAction.OpenChat -> {
                MessageManager.openWhatsAppChat(context, action.contactName)
            }
            is NimoAction.HealthLog -> {
                HealthManager.logHealthData(context, action.logQuery)
            }
            is NimoAction.SetAlarm -> {
                AlarmManager.setAlarm(context, action.hour, action.minute, isTomorrow = action.isTomorrow)
            }
            is NimoAction.AskAlarmTime -> {
                AlarmManager.promptForAlarmTime(context) {
                    OverlayService.onMicTapped?.invoke()
                }
            }
            is NimoAction.CancelAlarms -> {
                AlarmManager.cancelAllAlarms(context)
            }
            is NimoAction.JustReply -> {
                // Speech handled via TTSManager
            }
        }
    }

    private fun openApp(context: Context, appName: String) {
        val cleanName = appName.lowercase().trim()
        val pm = context.packageManager

        // 1. Try known package map first, tolerating mis-heard/misspelled
        // names like "gogle", "whtapp", "wh app" via fuzzy matching.
        val resolvedKey = fuzzyResolveKnownAppKey(cleanName)
        val targetPackage = resolvedKey?.let { APP_PACKAGES[it] }
        if (targetPackage != null && pm.getLaunchIntentForPackage(targetPackage) != null) {
            val label = resolveAppLabel(pm, targetPackage) ?: resolvedKey.replaceFirstChar { it.uppercase() }
            launchWithHighlight(context, targetPackage, label)
            return
        }

        // 2. Try searching all installed apps dynamically by label or package
        // name - exact/substring match first, then a fuzzy fallback so a
        // garbled name for an app outside our known list can still resolve.
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val normalizedInput = normalizeForFuzzyMatch(cleanName)

        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString()
            val pkgName = info.activityInfo.packageName
            if (label.lowercase().contains(cleanName) || pkgName.lowercase().contains(cleanName)) {
                if (pm.getLaunchIntentForPackage(pkgName) != null) {
                    launchWithHighlight(context, pkgName, label)
                    return
                }
            }
        }

        if (normalizedInput.length >= 3) {
            var bestInfo: android.content.pm.ResolveInfo? = null
            var bestLabel: String? = null
            var bestDistance = Int.MAX_VALUE
            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString()
                val normalizedLabel = normalizeForFuzzyMatch(label)
                if (normalizedLabel.isBlank()) continue
                val distance = levenshteinDistance(normalizedInput, normalizedLabel)
                val maxAllowed = maxOf(1, (normalizedLabel.length * 0.35).toInt())
                if (distance <= maxAllowed && distance < bestDistance) {
                    bestDistance = distance
                    bestInfo = info
                    bestLabel = label
                }
            }
            if (bestInfo != null) {
                val pkgName = bestInfo.activityInfo.packageName
                if (pm.getLaunchIntentForPackage(pkgName) != null) {
                    launchWithHighlight(context, pkgName, bestLabel ?: appName)
                    return
                }
            }
        }

        // 3. Fallback for YouTube
        if (cleanName.contains("youtube") || resolvedKey == "youtube") {
            try {
                val ytIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(ytIntent)
                return
            } catch (e: Exception) {
                // Ignore fallback error
            }
        }

        // 4. Fallback for Google / Search
        if (cleanName.contains("google") || resolvedKey == "google") {
            try {
                val searchIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(searchIntent)
                return
            } catch (e: Exception) {
                // Ignore fallback error
            }
        }

        TTSManager.speak("Couldn't find $appName installed")
    }

    private fun launchWithHighlight(context: Context, packageName: String, appLabel: String) {
        val intent = Intent(context, com.example.myapp.overlay.AppLaunchActivity::class.java).apply {
            putExtra(com.example.myapp.overlay.AppLaunchActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(com.example.myapp.overlay.AppLaunchActivity.EXTRA_APP_LABEL, appLabel)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun resolveAppLabel(pm: android.content.pm.PackageManager, packageName: String): String? {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun searchWeb(context: Context, query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(webIntent)
        }
    }

    private fun callContact(context: Context, contactName: String) {
        CallManager.prepareCall(context, contactName) {
            OverlayService.onMicTapped?.invoke()
        }
    }
}