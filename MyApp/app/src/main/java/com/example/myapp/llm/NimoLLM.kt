package com.example.myapp.llm

import android.content.Context
import android.util.Log
import com.example.myapp.BuildConfig
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Sends the user's query to an LLM and asks it to respond with a small
 * JSON "action" object instead of free text.
 */
object NimoLLM {

    private const val TAG = "NimoLLM"
    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.3-70b-versatile"

    private val client = OkHttpClient()

    private const val SYSTEM_PROMPT_ENGLISH = """
You are Nimo, a voice assistant for elderly users on Android.
Always reply with ONLY a JSON object, no other text, matching one of these shapes:

1. Open an app:
{"action": "open_app", "app_name": "google", "reply": "Opening Google for you."}

2. Search the web:
{"action": "search_web", "query": "weather in Bangalore", "reply": "Here's what I found."}

3. Call a contact by name:
{"action": "call_contact", "contact_name": "Rahul", "reply": "Calling Rahul now."}

4. Just answer in speech/text, no device action needed:
{"action": "reply", "reply": "Your answer here."}

5. Open a WhatsApp chat with a contact, without sending any message yet (e.g. "open whatsapp and open Sachin chat", "open chat with Sachin"):
{"action": "open_chat", "contact_name": "Sachin", "is_whatsapp": true, "reply": "Opening WhatsApp chat with Sachin."}

Rules:
- ALWAYS include a short, warm, simple "reply" string meant to be read aloud to an elderly person.
- Pick "app_name" from common apps: google, youtube, whatsapp, phone, camera, gallery, settings, chrome.
- Only use call_contact if the user clearly names a person to call.
- Nimo can directly help with emergency calls, contact calls, alarms, opening apps, web searches, reading/sending SMS or WhatsApp messages, and simple health logs.
- If asked what you can do, give a brief description of these capabilities and one or two example voice commands.
- Do not claim to perform actions that are not supported. For requests such as booking a ride, ordering food, transferring money, or sending email, say you cannot do it directly and give exactly three simple steps for the user to do it on their phone.
- For payments, never ask the user to tell you their PIN or password; remind them to verify the recipient and amount in their payment app.
- Respond with valid JSON only — no markdown, no code fences, no extra commentary.
"""

    private const val SYSTEM_PROMPT_HINDI = """
You are Nimo, a voice assistant for elderly users on Android.
Always reply with ONLY a JSON object, no other text, matching one of these shapes:

1. Open an app:
{"action": "open_app", "app_name": "google", "reply": "आपके लिए गूगल खोल रहा हूँ।"}

2. Search the web:
{"action": "search_web", "query": "weather in Bangalore", "reply": "यह रहा जो मुझे मिला।"}

3. Call a contact by name:
{"action": "call_contact", "contact_name": "Rahul", "reply": "राहुल को कॉल कर रहा हूँ।"}

4. Just answer in speech/text, no device action needed:
{"action": "reply", "reply": "नमस्ते! मैं निमो हूँ।"}

5. Open a WhatsApp chat with a contact, without sending any message yet:
{"action": "open_chat", "contact_name": "Sachin", "is_whatsapp": true, "reply": "सचिन के साथ व्हाट्सएप चैट खोली जा रही है।"}

Rules:
- CRITICAL: The user wants responses in HINDI. Even if the user types or speaks in English or Hinglish (e.g., 'open youtube', 'who are you'), you MUST write the 'reply' string in simple, warm Hindi using Devanagari script.
- निमो सीधे इमरजेंसी कॉल, संपर्कों को कॉल, अलार्म, ऐप खोलने, वेब खोज, SMS या WhatsApp संदेश पढ़ने/भेजने और सामान्य स्वास्थ्य रिकॉर्ड में मदद कर सकता है।
- यदि उपयोगकर्ता पूछे कि आप क्या कर सकते हैं, तो इन सुविधाओं का छोटा-सा परिचय और एक या दो उदाहरण दें।
- जो काम समर्थित नहीं हैं, उन्हें करने का दावा न करें। कैब बुक करने, खाना मँगाने, पैसे भेजने या ईमेल भेजने जैसे अनुरोधों पर विनम्रता से मना करके फोन पर करने के ठीक तीन आसान चरण बताएँ।
- भुगतान के लिए कभी PIN या पासवर्ड न पूछें; भुगतान ऐप में प्राप्तकर्ता और राशि जाँचने की याद दिलाएँ।
- Pick "app_name" from common apps: google, youtube, whatsapp, phone, camera, gallery, settings, chrome.
- Only use call_contact if the user clearly names a person to call.
- Respond with valid JSON only — no markdown, no code fences, no extra commentary.
"""

    interface Callback {
        fun onResult(action: NimoAction)
        fun onError(message: String)
    }

    fun ask(context: Context, userText: String, callback: Callback) {
        val lowerText = userText.lowercase().trim()

        // 1. Immediate Stop Command ("nimo stop", "stop", "quiet", "ruk jao")
        if (lowerText == "stop" || lowerText == "nimo stop" || lowerText == "stop nimo" ||
            lowerText == "quiet" || lowerText == "shut up" || lowerText.contains("ruk jao") ||
            lowerText.contains("रुक जाओ") || lowerText.contains("बस")
        ) {
            TTSManager.stop()
            CallManager.cancelPending()
            AlarmManager.cancelPending()
            val reply = if (LanguageManager.isHindi()) "रुक गया।" else "Stopped."
            callback.onResult(NimoAction.JustReply(reply))
            return
        }

        // 2. Language switching commands
        if (lowerText.contains("switch to hindi") || lowerText.contains("nimo switch to hindi") ||
            lowerText == "hindi" || lowerText.contains("hindi me") || lowerText.contains("हिंदी")
        ) {
            LanguageManager.setLanguage(context, LanguageManager.Language.HINDI)
            callback.onResult(NimoAction.JustReply("नमस्ते! अब मैं हिंदी में सहायता करूँगा।"))
            return
        }

        if (lowerText.contains("switch to english") || lowerText.contains("nimo switch to english") ||
            lowerText == "english" || lowerText.contains("english me")
        ) {
            LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
            callback.onResult(NimoAction.JustReply("Switched to English mode."))
            return
        }

        // 3. Local rule-based action match
        val localAction = tryLocalFallback(context, userText)
        if (localAction != null) {
            Log.d(TAG, "Local action matched for: \"$userText\" -> ${localAction.javaClass.simpleName}")
            callback.onResult(localAction)
            return
        }

        val apiKey = BuildConfig.GROQ_API_KEY.trim()

        if (apiKey.isBlank()) {
            Log.w(TAG, "GROQ_API_KEY is missing or empty in local.properties.")
            val fallbackMsg = if (LanguageManager.isHindi()) {
                "नमस्ते! मैं निमो हूँ। मैं इमरजेंसी कॉल, संदेश, अलार्म और ऐप्स संभाल सकता हूँ।"
            } else {
                "I am Nimo! I can handle emergency calls, read/send messages, set alarms, and open apps."
            }
            callback.onResult(NimoAction.JustReply(fallbackMsg))
            return
        }

        val systemPrompt = if (LanguageManager.isHindi()) SYSTEM_PROMPT_HINDI else SYSTEM_PROMPT_ENGLISH

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("temperature", 0.3)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Network failure: ${e.message}")
                val fallbackMsg = if (LanguageManager.isHindi()) {
                    "मैं निमो हूँ! मैं इमरजेंसी कॉल, संदेश, अलार्म और ऐप्स संभाल सकता हूँ।"
                } else {
                    "I am Nimo! I can handle emergency calls, read/send messages, set alarms, and open apps."
                }
                callback.onResult(NimoAction.JustReply(fallbackMsg))
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string()
                Log.d(TAG, "Groq response (HTTP ${response.code}): $raw")

                if (!response.isSuccessful || raw.isNullOrBlank()) {
                    val apiError = parseApiError(raw)
                    Log.e(TAG, "LLM API Error (HTTP ${response.code}): $apiError")

                    val fallbackMsg = if (LanguageManager.isHindi()) {
                        "मैं निमो हूँ! मैं अलार्म लगा सकता हूँ, संदेश पढ़ सकता हूँ और ऐप्स खोल सकता हूँ।"
                    } else {
                        "I am Nimo! I can set alarms, read messages, open apps, or search Google."
                    }
                    callback.onResult(NimoAction.JustReply(fallbackMsg))
                    return
                }

                try {
                    val json = JSONObject(raw)
                    if (json.has("error")) {
                        val fallbackMsg = if (LanguageManager.isHindi()) {
                            "मैं निमो हूँ! मैं आपकी सहायता कर सकता हूँ।"
                        } else {
                            "I am Nimo! How can I help you today?"
                        }
                        callback.onResult(NimoAction.JustReply(fallbackMsg))
                        return
                    }

                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        val fallbackMsg = if (LanguageManager.isHindi()) {
                            "नमस्ते! मैं निमो हूँ, आपकी क्या सहायता कर सकता हूँ?"
                        } else {
                            "I am Nimo! How can I help you today?"
                        }
                        callback.onResult(NimoAction.JustReply(fallbackMsg))
                        return
                    }

                    val content = choices
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                        .trim()

                    val actionJson = parseJsonFromContent(content)
                    if (actionJson != null) {
                        callback.onResult(NimoAction.fromJson(actionJson))
                    } else {
                        callback.onResult(NimoAction.JustReply(content))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response: ${e.message}", e)
                    val fallbackMsg = if (LanguageManager.isHindi()) {
                        "मैं निमो हूँ! मैं आपकी सहायता कर सकता हूँ।"
                    } else {
                        "I am Nimo! How can I help you today?"
                    }
                    callback.onResult(NimoAction.JustReply(fallbackMsg))
                }
            }
        })
    }

    private fun parseApiError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Empty response from server"
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                json.getJSONObject("error").optString("message", raw)
            } else raw
        } catch (e: Exception) {
            raw
        }
    }

    private fun parseJsonFromContent(content: String): JSONObject? {
        if (content.isBlank()) return null
        return try {
            var text = content
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start != -1 && end != -1 && end > start) {
                text = text.substring(start, end + 1)
            }
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryLocalFallback(context: Context, userText: String): NimoAction? {
        val lower = userText.lowercase().trim()
        val isHindi = LanguageManager.isHindi()

        // Strip wake word prefix if present
        val targetText = lower
            .replace("^\\b(nimo|neemo|nimmo|ok nimo|hey nimo|hello nimo|hi nimo|ok|hey|hello|hi|निमो)\\b".toRegex(), "")
            .trim()
            .ifBlank { lower }

        // 1. STOP COMMAND
        if (targetText == "stop" || targetText == "quiet" || targetText == "shut up" ||
            targetText == "ruk jao" || targetText == "रुक जाओ" || targetText == "बस"
        ) {
            TTSManager.stop()
            CallManager.cancelPending()
            AlarmManager.cancelPending()
            val reply = if (isHindi) "रुक गया।" else "Stopped."
            return NimoAction.JustReply(reply)
        }

        // 2. EMERGENCY SOS
        if (targetText == "emergency" || targetText == "sos" || targetText.contains("emergency call") ||
            targetText == "save me" || targetText == "help me" || targetText.contains("मदद") ||
            targetText.contains("इमरजेंसी") || targetText.contains("बचाओ")
        ) {
            val reply = if (isHindi) "आपकी सुरक्षा के लिए तुरंत इमरजेंसी कॉल लगाया जा रहा है।" else "Emergency SOS! Placing call immediately."
            return NimoAction.EmergencyCall(reply)
        }

        // 3. Language switching
        if (targetText.contains("switch to hindi") || targetText.contains("हिंदी में बात") || targetText.contains("हिंदी")) {
            LanguageManager.setLanguage(context, LanguageManager.Language.HINDI)
            return NimoAction.JustReply("नमस्ते! अब मैं हिंदी में सहायता करूँगा।")
        }
        if (targetText.contains("switch to english") || targetText.contains("अंग्रेजी") || targetText.contains("english me")) {
            LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
            return NimoAction.JustReply("Switched to English mode.")
        }

        // 4. UNSUPPORTED ACTION GUIDES (Cab, Food, Payment, Email)
        if (targetText.contains("cab") || targetText.contains("uber") || targetText.contains("ola") || targetText.contains("taxi")) {
            val reply = if (isHindi) {
                "मैं सीधे कैब बुक नहीं कर सकता, लेकिन ये चरण हैं:\n1. फोन में Uber या Ola ऐप खोलें।\n2. अपना पिकअप और ड्रॉप स्थान दर्ज करें।\n3. गाड़ी चुनें और 'Confirm' पर टैप करें।"
            } else {
                "I cannot book a cab directly, but here are the steps:\n1. Open Uber or Ola app on your phone.\n2. Enter your pickup and destination.\n3. Select your ride type and tap 'Confirm'."
            }
            return NimoAction.JustReply(reply)
        }

        if (targetText.contains("order food") || targetText.contains("zomato") || targetText.contains("swiggy") || targetText.contains("pizza")) {
            val reply = if (isHindi) {
                "मैं सीधे खाना ऑर्डर नहीं कर सकता, लेकिन ये चरण हैं:\n1. Zomato या Swiggy ऐप खोलें।\n2. अपनी पसंद की डिश या रेस्तरां खोजें।\n3. आइटम कार्ट में जोड़ें और 'Place Order' पर टैप करें।"
            } else {
                "I cannot order food directly, but here are the steps:\n1. Open Zomato or Swiggy on your phone.\n2. Search for your dish or restaurant.\n3. Add item to cart and proceed to payment."
            }
            return NimoAction.JustReply(reply)
        }

        if (targetText.contains("pay bill") || targetText.contains("send money") || targetText.contains("gpay") ||
            targetText.contains("phonepe") || targetText.contains("paytm") || targetText.contains("upi")
        ) {
            val reply = if (isHindi) {
                "सुरक्षा के लिए मैं सीधे पैसे नहीं भेज सकता, लेकिन ये चरण अपनाएँ:\n1. Google Pay या PhonePe खोलें।\n2. QR कोड स्कैन करें या प्राप्तकर्ता चुनें।\n3. नाम और राशि जाँचकर ऐप में सुरक्षित रूप से भुगतान पूरा करें; अपना PIN मुझे कभी न बताएँ।"
            } else {
                "For your security, I can’t transfer money directly. Here are the steps:\n1. Open Google Pay, PhonePe, or your payment app.\n2. Scan the QR code or choose the recipient.\n3. Check the recipient and amount, then complete payment securely in the app. Never share your PIN with me."
            }
            return NimoAction.JustReply(reply)
        }

        if (targetText.contains("send email") || targetText.contains("gmail") || targetText.contains("check email")) {
            val reply = if (isHindi) {
                "मैं सीधे ईमेल नहीं भेज सकता, लेकिन ये चरण हैं:\n1. अपने फोन में Gmail ऐप खोलें।\n2. 'Compose' बटन पर टैप करें।\n3. प्राप्तकर्ता का ईमेल, विषय और संदेश लिखकर 'Send' पर टैप करें।"
            } else {
                "I cannot compose emails directly, but here are the steps:\n1. Open Gmail app on your phone.\n2. Tap the 'Compose' button.\n3. Enter recipient, subject, and message, then tap 'Send'."
            }
            return NimoAction.JustReply(reply)
        }

        // 5. Read WhatsApp Messages or SMS
        if (targetText.contains("read message") || targetText.contains("read msg") ||
            targetText.contains("read whatsapp") || targetText.contains("message padho") ||
            targetText.contains("संदेश पढ़ो") || targetText.contains("read sms")
        ) {
            val isWhatsApp = targetText.contains("whatsapp")
            val senderName = targetText
                .replace("read message on whatsapp by", "")
                .replace("read message from", "")
                .replace("read whatsapp message from", "")
                .replace("read message on whatsapp from", "")
                .replace("read whatsapp from", "")
                .replace("read message", "")
                .replace("read msg", "")
                .replace("message padho", "")
                .replace("whatsapp", "")
                .replace("by", "")
                .replace("from", "")
                .replace("sms", "")
                .trim()
            val reply = if (isHindi) "$senderName का संदेश पढ़ा जा रहा है।" else "Reading message from $senderName."
            return NimoAction.ReadMessage(senderName, isWhatsApp, reply)
        }

        // 6. Send WhatsApp Message or SMS. Written to survive compound
        // phrasing like "open whatsapp and send hi message to sachin" where
        // "send" and "message" aren't adjacent, and messages with no
        // "saying"/"say" keyword at all ("send hi message to sachin").
        val sendMessageTrigger = Regex("\\bsend\\b.*\\b(message|whatsapp|sms)\\b") // "send ... message/whatsapp/sms"
        val looksLikeSendMessage = sendMessageTrigger.containsMatchIn(targetText) ||
                targetText.contains("whatsapp message") || targetText.contains("send sms") ||
                (targetText.contains("whatsapp") && targetText.contains(" to ") && targetText.contains("saying"))

        if (looksLikeSendMessage) {
            val isWhatsApp = targetText.contains("whatsapp")

            // Drop a leading "open <app> and" so it doesn't get treated as an
            // app-launch request instead of (or in addition to) the message.
            val stripped = targetText.replace(Regex("^open\\s+\\S+\\s+and\\s+"), "").trim()

            var contactName: String? = null
            var msgBody: String? = null

            // Pattern A: "... <body> message to <contact>" (e.g. "send hi message to sachin")
            Regex("send\\s+(.+?)\\s+message\\s+to\\s+([a-zA-Z\u0900-\u097F ]+?)(?:\\s+saying\\s+(.+))?$")
                .find(stripped)?.let { m ->
                    msgBody = m.groupValues[1].trim()
                    contactName = m.groupValues[2].trim()
                    if (m.groupValues[3].isNotBlank()) msgBody = m.groupValues[3].trim()
                }

            // Pattern B: "... to <contact> saying/say <body>"
            if (contactName == null) {
                Regex("to\\s+([a-zA-Z\u0900-\u097F]+)\\s+(?:saying|say|likho|bolo)\\s+(.+)$")
                    .find(stripped)?.let { m ->
                        contactName = m.groupValues[1].trim()
                        msgBody = m.groupValues[2].trim()
                    }
            }

            // Fallback: previous header/body split on "saying"/"say"/etc.
            if (contactName == null) {
                val parts = stripped.split("saying", "say", "likho", "bolo")
                val header = parts.getOrNull(0) ?: stripped
                msgBody = parts.getOrNull(1)?.trim() ?: "Hello"
                contactName = header
                    .replace("send message to", "")
                    .replace("send whatsapp to", "")
                    .replace("send whatsapp message to", "")
                    .replace("send whatsapp", "")
                    .replace("send message", "")
                    .replace("on whatsapp", "")
                    .replace("send sms to", "")
                    .trim()
            }

            val finalContact = contactName.orEmpty().trim()
            val finalBody = msgBody?.trim().takeUnless { it.isNullOrBlank() } ?: "Hello"

            if (finalContact.isNotBlank()) {
                val reply = if (isHindi) "$finalContact को संदेश भेजा जा रहा है।" else "Preparing message for $finalContact."
                return NimoAction.SendMessage(finalContact, finalBody, isWhatsApp, reply)
            }
        }

        // 6b. Open a specific chat with a contact (WhatsApp) without sending
        // any message — e.g. "open whatsapp and open sachin chat", "open
        // chat with sachin", "open sachin's whatsapp chat". Checked before
        // rule 11 (generic Open App) so a compound phrase like the first
        // example isn't swallowed whole as one garbled app name.
        if (targetText.contains("chat") && !looksLikeSendMessage) {
            // Drop a leading "open <app> and" so "open whatsapp and open
            // sachin chat" isn't mistaken for a single app-launch request.
            val stripped = targetText.replace(Regex("^open\\s+\\S+\\s+and\\s+"), "").trim()

            val contactName = stripped
                .replace("whatsapp", "")
                .replace("open", "")
                .replace("chat with", "")
                .replace("with", "")
                .replace("chat", "")
                .replace("'s", "")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ")

            if (contactName.isNotBlank()) {
                val reply = if (isHindi) {
                    "$contactName के साथ व्हाट्सएप चैट खोली जा रही है।"
                } else {
                    "Opening WhatsApp chat with $contactName."
                }
                return NimoAction.OpenChat(contactName, isWhatsApp = true, reply)
            }
        }

        // 7. Health Log & Summary
        if (targetText.contains("health") || targetText.contains("blood pressure") ||
            targetText.contains("bp") || targetText.contains("sugar") ||
            targetText.contains("बीपी") || targetText.contains("शुगर") || targetText.contains("स्वास्थ्य")
        ) {
            val reply = if (isHindi) "स्वास्थ्य रिकॉर्ड चेक किया जा रहा है।" else "Checking your health records."
            return NimoAction.HealthLog(targetText, reply)
        }

        // 8. Cancel Alarms
        if (targetText.contains("cancel all alarms") || targetText.contains("delete all alarms") ||
            targetText.contains("cancel alarm") || targetText.contains("delete alarm") ||
            targetText.contains("अलार्म रद्द") || targetText.contains("अलार्म बंद") ||
            targetText.contains("alarm radd") || targetText.contains("alarm cancel")
        ) {
            val reply = if (isHindi) "आपके लिए अलार्म ऐप खोल रहा हूँ।" else "Opening Alarms to manage or cancel alarms."
            return NimoAction.CancelAlarms(reply)
        }

        // 9. Create / Set Alarm
        if (targetText.contains("alarm") || targetText.contains("अलार्म") || targetText.contains("wake me up") || targetText.contains("जगाओ")) {
            val parsedTime = AlarmManager.parseTimeFromSpeech(targetText)
            if (parsedTime != null) {
                val (hour, minute, isTomorrow) = parsedTime
                val reply = if (isHindi) "अलार्म सेट किया जा रहा है।" else "Setting alarm for you."
                return NimoAction.SetAlarm(hour, minute, isTomorrow, reply)
            } else {
                val reply = if (isHindi) "किस समय का अलार्म लगाना है?" else "What time should I set the alarm for?"
                return NimoAction.AskAlarmTime(reply)
            }
        }

        // 10. Call Contact
        if (targetText.startsWith("call ") || targetText.contains("कॉल") || targetText.contains("फोन") ||
            targetText.contains("ko call") || targetText.contains("ko phone")
        ) {
            val contactName = targetText
                .removePrefix("call ")
                .replace("को कॉल करो", "")
                .replace("को कॉल", "")
                .replace("को फोन करो", "")
                .replace("को फोन", "")
                .replace("कॉल करो", "")
                .replace("कॉल", "")
                .replace("फोन करो", "")
                .replace("फोन", "")
                .replace("ko call karo", "")
                .replace("ko call", "")
                .replace("ko phone karo", "")
                .replace("ko phone", "")
                .trim()
            if (contactName.isNotEmpty()) {
                val reply = if (isHindi) "$contactName को कॉल कर रहा हूँ।" else "Calling $contactName."
                return NimoAction.CallContact(contactName, reply)
            }
        }

        // 11. Open App
        if (targetText.startsWith("open ") || targetText.contains("खोलो") || targetText.contains("चलाओ") ||
            targetText.contains("kholo") || targetText.contains("chalao")
        ) {
            val appName = targetText
                .removePrefix("open ")
                .replace("खोलो", "")
                .replace("चलाओ", "")
                .replace("kholo", "")
                .replace("chalao", "")
                .trim()
            if (appName.isNotEmpty()) {
                // Speak the canonical app name ("WhatsApp") rather than
                // whatever garbled text speech recognition produced
                // ("whtapp", "wh app"), even though execution itself
                // fuzzy-resolves the raw name too.
                val displayName = ActionExecutor.canonicalAppName(appName)
                val reply = if (isHindi) "आपके लिए $displayName खोल रहा हूँ।" else "Opening $displayName for you."
                return NimoAction.OpenApp(appName, reply)
            }
        }

        // 12. Search Web
        if (targetText.startsWith("search ") || targetText.startsWith("google ") ||
            targetText.contains("खोजो") || targetText.contains("khojo")
        ) {
            val query = targetText
                .removePrefix("search ")
                .removePrefix("google ")
                .replace("खोजो", "")
                .replace("khojo", "")
                .trim()
            if (query.isNotEmpty()) {
                val reply = if (isHindi) "$query खोज रहा हूँ।" else "Searching for $query."
                return NimoAction.SearchWeb(query, reply)
            }
        }

        // 13. Capability & Help Questions
        if (targetText.contains("what can you do") || targetText.contains("what can nimo do") ||
            targetText.contains("what all u can do") ||
            targetText.contains("what all you can do") || targetText.contains("what can u do") ||
            targetText.contains("what u can do") || targetText.contains("what are your capabilities") ||
            targetText.contains("what can you help me with") || targetText.contains("how can you help") ||
            targetText.contains("who are you") ||
            targetText.contains("features") || targetText.contains("what do you do") ||
            targetText.contains("kya kar sakte ho") || targetText.contains("क्या कर सकते हो") ||
            targetText.contains("क्या-क्या कर सकते") || targetText.contains("तुम कौन हो") ||
            targetText.contains("आप क्या कर सकते") || targetText.contains("help")
        ) {
            val reply = if (isHindi) {
                "मैं निमो हूँ। मैं SOS और संपर्कों को कॉल, अलार्म, ऐप खोलने, वेब खोज, संदेश और सामान्य स्वास्थ्य रिकॉर्ड में मदद कर सकता हूँ। कहें: 'रीना को कॉल करो' या 'सुबह 7 बजे अलार्म लगाओ'; मैं हिंदी-अंग्रेजी बदल सकता हूँ और रुकने पर बोलना बंद कर सकता हूँ। मैं सीधे कैब या खाना बुक, पैसे ट्रांसफर या ईमेल नहीं भेज सकता, लेकिन फोन पर करने के तीन आसान चरण बता सकता हूँ।"
            } else {
                "I am Nimo. I can help with emergency and contact calls, alarms, opening apps, web searches, messages, and simple health logs. Try “Call Maya” or “Set an alarm for 7 AM”; I can also switch languages and stop speaking when asked. I can’t directly book rides or food, transfer money, or send email, but I can guide you through three steps on your phone."
            }
            return NimoAction.JustReply(reply)
        }

        // 14. Greetings
        if (targetText == "hi" || targetText == "hello" || targetText == "hey" ||
            targetText == "namaste" || targetText == "नमस्ते"
        ) {
            val reply = if (isHindi) {
                "नमस्ते! मैं निमो हूँ, आपकी क्या सहायता कर सकता हूँ?"
            } else {
                "Hello! I am Nimo, your assistant. How can I help you today?"
            }
            return NimoAction.JustReply(reply)
        }

        return null
    }
}

/** Parsed, type-safe representation of what the LLM wants Nimo to do */
sealed class NimoAction(val reply: String) {
    class OpenApp(val appName: String, reply: String) : NimoAction(reply)
    class SearchWeb(val query: String, reply: String) : NimoAction(reply)
    class CallContact(val contactName: String, reply: String) : NimoAction(reply)
    class EmergencyCall(reply: String) : NimoAction(reply)
    class ReadMessage(val senderName: String, val isWhatsApp: Boolean, reply: String) : NimoAction(reply)
    class SendMessage(val contactName: String, val messageText: String, val isWhatsApp: Boolean, reply: String) : NimoAction(reply)
    class OpenChat(val contactName: String, val isWhatsApp: Boolean, reply: String) : NimoAction(reply)
    class HealthLog(val logQuery: String, reply: String) : NimoAction(reply)
    class SetAlarm(val hour: Int, val minute: Int, val isTomorrow: Boolean, reply: String) : NimoAction(reply)
    class AskAlarmTime(reply: String) : NimoAction(reply)
    class CancelAlarms(reply: String) : NimoAction(reply)
    class JustReply(reply: String) : NimoAction(reply)

    companion object {
        fun fromJson(json: JSONObject): NimoAction {
            val reply = json.optString("reply", json.optString("message", json.optString("content", "")))
            val defaultReply = if (LanguageManager.isHindi()) {
                "नमस्ते! मैं निमो हूँ, आपकी क्या सहायता कर सकता हूँ?"
            } else {
                "I am Nimo! How can I help you today?"
            }
            return when (json.optString("action")) {
                "open_app" -> OpenApp(json.optString("app_name"), reply)
                "search_web" -> SearchWeb(json.optString("query"), reply)
                "call_contact" -> CallContact(json.optString("contact_name"), reply)
                "emergency" -> EmergencyCall(reply)
                "read_message" -> ReadMessage(json.optString("sender_name"), json.optBoolean("is_whatsapp"), reply)
                "send_message" -> SendMessage(json.optString("contact_name"), json.optString("message"), json.optBoolean("is_whatsapp"), reply)
                "open_chat" -> OpenChat(json.optString("contact_name"), json.optBoolean("is_whatsapp", true), reply)
                "cancel_alarms" -> CancelAlarms(reply)
                else -> JustReply(if (reply.isNotBlank()) reply else defaultReply)
            }
        }
    }
}