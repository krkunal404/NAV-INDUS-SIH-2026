package com.example.myapp.llm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast

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
                // No device action — the reply text alone is the response.
                // Wire this into your TTS / overlay text display.
            }
        }
    }

    private fun openApp(context: Context, appName: String) {
        val packageName = APP_PACKAGES[appName.lowercase()]
        try {
            if (packageName != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return
                }
            }
            Toast.makeText(context, "Couldn't find $appName installed", Toast.LENGTH_SHORT).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Couldn't open $appName", Toast.LENGTH_SHORT).show()
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
            // Fallback: open Google search directly in browser
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(webIntent)
        }
    }

    /**
     * Opens the dialer with the contact's number pre-filled (ACTION_DIAL).
     * This does NOT require the CALL_PHONE permission — the user still has
     * to tap the call button themselves, which is safer and store-friendly.
     * If you want fully automatic calling (no tap needed), switch to
     * ACTION_CALL and request the CALL_PHONE runtime permission separately.
     */
    private fun callContact(context: Context, contactName: String) {
        val number = lookupContactNumber(context, contactName)
        if (number == null) {
            Toast.makeText(context, "Couldn't find $contactName in contacts", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun lookupContactNumber(context: Context, name: String): String? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return it.getString(numberIndex)
            }
        }
        return null
    }
}
