package com.example.myapp.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.example.myapp.notifications.WhatsAppNotificationListener
import com.example.myapp.overlay.OverlayService
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager
import java.net.URLEncoder

object MessageManager {

    private const val TAG = "MessageManager"

    fun readMessages(context: Context, senderName: String, isWhatsApp: Boolean = false) {
        if (isWhatsApp) {
            readWhatsAppMessage(context, senderName)
        } else {
            readSmsMessage(context, senderName)
        }
    }

    private fun readWhatsAppMessage(context: Context, senderName: String) {
        val isHindi = LanguageManager.isHindi()

        if (!WhatsAppNotificationListener.isEnabled(context)) {
            val msg = if (isHindi) {
                "व्हाट्सएप संदेश पढ़ने के लिए नोटिफिकेशन एक्सेस चालू करना होगा। सेटिंग्स खोल रहा हूँ, वहाँ Nimo को ऑन करें।"
            } else {
                "To read WhatsApp messages, notification access needs to be turned on for Nimo once. Opening settings now — please switch Nimo on there."
            }
            OverlayService.updateStatus(msg)
            TTSManager.speak(msg)
            WhatsAppNotificationListener.openAccessSettings(context)
            return
        }

        val match = WhatsAppNotificationListener.latestMessageFrom(senderName)
        if (match != null) {
            val (actualSender, text) = match
            val reply = if (isHindi) {
                "$actualSender का व्हाट्सएप संदेश: $text"
            } else {
                "WhatsApp message from $actualSender: $text"
            }
            OverlayService.updateStatus(reply)
            TTSManager.speak(reply)
        } else {
            val msg = if (isHindi) {
                "$senderName से कोई हाल का व्हाट्सएप संदेश नहीं मिला। मैं केवल वे संदेश पढ़ सकता हूँ जो नोटिफिकेशन एक्सेस चालू होने के बाद आए हों।"
            } else {
                "No recent WhatsApp message found from $senderName. I can only read messages that arrived after notification access was turned on."
            }
            OverlayService.updateStatus(msg)
            TTSManager.speak(msg)
        }
    }

    private fun readSmsMessage(context: Context, senderName: String) {
        val isHindi = LanguageManager.isHindi()
        val cleanSender = senderName.lowercase().trim()

        try {
            // Resolve the spoken name to a phone number first, since SMS
            // sender addresses are numbers, not contact names.
            val contact = CallManager.lookupContact(context, cleanSender)
            val contactDigits = contact?.number?.filter { it.isDigit() }

            val contentResolver = context.contentResolver
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE),
                null,
                null,
                Telephony.Sms.Inbox.DEFAULT_SORT_ORDER
            )

            var foundMessage: String? = null
            var actualSender = senderName

            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.Inbox.BODY)

                while (it.moveToNext()) {
                    val address = if (addressIdx != -1) it.getString(addressIdx) ?: "" else ""
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) ?: "" else ""
                    val addressDigits = address.filter { ch -> ch.isDigit() }

                    val matchesByNumber = contactDigits != null &&
                            addressDigits.isNotEmpty() &&
                            (addressDigits.endsWith(contactDigits.takeLast(10)) ||
                                    contactDigits.endsWith(addressDigits.takeLast(10)))
                    val matchesByRawText = cleanSender.isBlank() ||
                            address.lowercase().contains(cleanSender)

                    if (matchesByNumber || matchesByRawText) {
                        foundMessage = body
                        actualSender = if (matchesByNumber) (contact?.name ?: address) else address
                        break
                    }
                }
            }

            if (foundMessage != null) {
                val reply = if (isHindi) {
                    "$actualSender का संदेश: $foundMessage"
                } else {
                    "Message from $actualSender: $foundMessage"
                }
                OverlayService.updateStatus(reply)
                TTSManager.speak(reply)
            } else {
                val msg = if (isHindi) {
                    "$senderName से कोई नया संदेश नहीं मिला।"
                } else {
                    "No recent messages found from $senderName."
                }
                OverlayService.updateStatus(msg)
                TTSManager.speak(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading messages: ${e.message}")
            val errorMsg = if (isHindi) "संदेश पढ़ने में त्रुटि हुई।" else "Could not read messages."
            OverlayService.updateStatus(errorMsg)
            TTSManager.speak(errorMsg)
        }
    }

    fun sendWhatsAppMessage(context: Context, contactName: String, messageText: String) {
        val isHindi = LanguageManager.isHindi()
        val contact = CallManager.lookupContact(context, contactName)

        val number = contact?.number?.replace("[^0-9]".toRegex(), "") ?: ""
        val encodedText = try { URLEncoder.encode(messageText, "UTF-8") } catch (e: Exception) { messageText }

        val reply = if (isHindi) {
            "$contactName को व्हाट्सएप पर संदेश भेजा जा रहा है।"
        } else {
            "Sending WhatsApp message to $contactName."
        }
        OverlayService.updateStatus(reply)
        TTSManager.speak(reply)

        val intent = if (number.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encodedText"))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, messageText)
                setPackage("com.whatsapp")
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp: ${e.message}")
        }
    }

    /**
     * Opens the WhatsApp conversation with a contact directly, without
     * sending any message — used for commands like "open Sachin's WhatsApp
     * chat" where the user just wants the chat screen up, not to send text.
     */
    fun openWhatsAppChat(context: Context, contactName: String) {
        val isHindi = LanguageManager.isHindi()
        val contact = CallManager.lookupContact(context, contactName)
        val number = contact?.number?.replace("[^0-9]".toRegex(), "") ?: ""

        val statusMsg = if (isHindi) {
            "$contactName के साथ व्हाट्सएप चैट खोली जा रही है।"
        } else {
            "Opening WhatsApp chat with $contactName."
        }
        OverlayService.updateStatus(statusMsg)

        val intent = if (number.isNotBlank()) {
            // No "text=" param, so it opens straight to the chat with
            // nothing pre-filled and nothing sent.
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number"))
        } else {
            // Couldn't resolve a contact number — at least open WhatsApp
            // itself so the user can find the chat manually.
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.whatsapp")
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp chat: ${e.message}")
            val errorMsg = if (isHindi) "व्हाट्सएप चैट खोलने में समस्या हुई।" else "Could not open the WhatsApp chat."
            OverlayService.updateStatus(errorMsg)
            TTSManager.speak(errorMsg)
        }
    }

    fun sendSmsMessage(context: Context, contactName: String, messageText: String) {
        val isHindi = LanguageManager.isHindi()
        val contact = CallManager.lookupContact(context, contactName)
        val number = contact?.number ?: ""

        val reply = if (isHindi) {
            "$contactName को एसएमएस भेजा जा रहा है।"
        } else {
            "Opening SMS for $contactName."
        }
        OverlayService.updateStatus(reply)
        TTSManager.speak(reply)

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", messageText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SMS intent: ${e.message}")
        }
    }
}