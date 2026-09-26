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

    fun execute(context: Context, action: NimoAction) {
        when (action) {
            is NimoAction.OpenApp -> openApp(context, action.appName)
            is NimoAction.SearchWeb -> searchWeb(context, action.query)
            is NimoAction.CallContact -> callContact(context, action.contactName)
            is NimoAction.JustReply -> {
                // Response speech is handled via TTSManager.speak(action.reply)
            }
        }
    }

    private fun openApp(context: Context, appName: String) {
        val cleanName = appName.lowercase().trim()
        val pm = context.packageManager

        // 1. Try known package map first
        val targetPackage = APP_PACKAGES[cleanName]
        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return
            }
        }

        // 2. Try searching all installed apps dynamically by label or package name
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().lowercase()
            val pkgName = info.activityInfo.packageName.lowercase()
            if (label.contains(cleanName) || pkgName.contains(cleanName)) {
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return
                }
            }
        }

        // 3. Fallback for YouTube
        if (cleanName.contains("youtube")) {
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
        if (cleanName.contains("google")) {
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
