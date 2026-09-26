package com.example.myapp.llm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapp.overlay.OverlayService
import com.example.myapp.speech.TTSManager

object CallManager {

    private const val TAG = "CallManager"

    data class ContactInfo(val name: String, val number: String)

    private var pendingContact: ContactInfo? = null
    var isAwaitingConfirmation: Boolean = false
        private set

    fun prepareCall(context: Context, contactName: String, onPromptUser: () -> Unit) {
        val contact = lookupContact(context, contactName)
        if (contact == null) {
            val msg = "Couldn't find $contactName in your contacts"
            OverlayService.updateStatus(msg)
            TTSManager.speak(msg)
            isAwaitingConfirmation = false
            pendingContact = null
            return
        }

        pendingContact = contact
        isAwaitingConfirmation = true

        val prompt = "Found ${contact.name} (${contact.number}). Should I call ${contact.name}?"
        OverlayService.updateStatus(prompt)

        TTSManager.speak("Found ${contact.name}. Should I call ${contact.name}?") {
            onPromptUser()
        }
    }

    fun handleUserConfirmation(context: Context, userSpeech: String): Boolean {
        if (!isAwaitingConfirmation || pendingContact == null) return false

        val lower = userSpeech.lowercase().trim()
        val isAffirmative = listOf("yes", "yeah", "yep", "sure", "call", "do it", "ok", "okay", "please", "हाँ", "हा", "कॉल करो", "हाँ करो")
            .any { lower.contains(it) }

        val isNegative = listOf("no", "nope", "don't", "dont", "cancel", "stop", "नहीं", "ना", "मत करो", "रद्द करो")
            .any { lower.contains(it) }

        val contact = pendingContact!!

        if (isAffirmative) {
            isAwaitingConfirmation = false
            pendingContact = null

            val reply = "Calling ${contact.name} now."
            OverlayService.updateStatus(reply)
            TTSManager.speak(reply)
            makeCall(context, contact.number)
            return true
        } else if (isNegative) {
            isAwaitingConfirmation = false
            pendingContact = null

            val reply = "Okay, call canceled."
            OverlayService.updateStatus(reply)
            TTSManager.speak(reply)
            return true
        }

        isAwaitingConfirmation = false
        pendingContact = null
        return false
    }

    fun cancelPending() {
        isAwaitingConfirmation = false
        pendingContact = null
    }

    fun makeCall(context: Context, number: String) {
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
            Log.e(TAG, "Failed to place call to $number: ${e.message}")
        }
    }

    fun lookupContact(context: Context, name: String): ContactInfo? {
        val cleanSearch = name.lowercase().trim().replace("[^a-z0-9\\s]".toRegex(), "")
        if (cleanSearch.isBlank()) return null

        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        val contactsList = mutableListOf<ContactInfo>()
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val displayName = if (nameIdx != -1) it.getString(nameIdx) ?: "" else ""
                val number = if (numberIdx != -1) it.getString(numberIdx) ?: "" else ""
                if (displayName.isNotBlank() && number.isNotBlank()) {
                    contactsList.add(ContactInfo(displayName, number))
                }
            }
        }

        // 1. Direct or substring match
        val exactMatch = contactsList.firstOrNull {
            val cName = it.name.lowercase().trim()
            cName == cleanSearch || cName.contains(cleanSearch) || cleanSearch.contains(cName)
        }
        if (exactMatch != null) return exactMatch

        // 2. Token match (individual word tokens)
        val searchTokens = cleanSearch.split("\\s+".toRegex()).filter { it.length >= 2 }
        for (contact in contactsList) {
            val contactTokens = contact.name.lowercase().split("\\s+".toRegex())
            for (st in searchTokens) {
                if (contactTokens.any { ct -> ct.contains(st) || st.contains(ct) }) {
                    return contact
                }
            }
        }

        return null
    }
}
