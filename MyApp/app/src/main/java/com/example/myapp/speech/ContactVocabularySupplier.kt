package com.example.myapp.speech

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray

object ContactVocabularySupplier {

    private const val TAG = "ContactVocab"

    fun buildVocabularyJson(context: Context): String {
        val words = mutableSetOf(
            "hello", "nimo", "ok", "hey", "neemo", "nimmo",
            "open", "call", "search", "google", "youtube", "whatsapp",
            "phone", "camera", "gallery", "settings", "chrome", "calculator",
            "yes", "yeah", "yep", "sure", "no", "nope", "cancel", "stop",
            "what", "can", "you", "do", "all", "help", "who", "are",
            "switch", "to", "hindi", "english", "namaste", "kholo", "chalao",
            "karo", "khojo", "baat", "kya", "kaise", "sakte", "ho", "ko",
            "[unk]"
        )

        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                null
            )
            cursor?.use {
                val idx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (it.moveToNext()) {
                    if (idx != -1) {
                        val name = it.getString(idx)
                        if (!name.isNullOrBlank()) {
                            words.add(name.lowercase().trim())
                            name.split("\\s+".toRegex()).forEach { token ->
                                val clean = token.lowercase().replace("[^a-z0-9]".toRegex(), "").trim()
                                if (clean.length >= 2) {
                                    words.add(clean)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read contacts for vocabulary: ${e.message}")
        }

        val jsonArray = JSONArray()
        words.forEach { jsonArray.put(it) }
        return jsonArray.toString()
    }
}
