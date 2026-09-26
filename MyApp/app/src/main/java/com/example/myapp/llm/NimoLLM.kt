package com.example.myapp.llm

import android.content.Context
import android.util.Log
import com.example.myapp.BuildConfig
import com.example.myapp.speech.LanguageManager
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

Rules:
- ALWAYS include a short, warm, simple "reply" string meant to be read aloud to an elderly person.
- Pick "app_name" from common apps: google, youtube, whatsapp, phone, camera, gallery, settings, chrome.
- Only use call_contact if the user clearly names a person to call.
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

Rules:
- CRITICAL: The user wants responses in HINDI. Even if the user types or speaks in English or Hinglish (e.g., 'open youtube', 'who are you'), you MUST write the 'reply' string in simple, warm Hindi using Devanagari script.
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

        // 1. Language switching commands
        if (lowerText.contains("switch to hindi") || lowerText.contains("nimo switch to hindi") ||
            lowerText == "hindi" || lowerText.contains("hindi me baat")
        ) {
            LanguageManager.setLanguage(context, LanguageManager.Language.HINDI)
            callback.onResult(NimoAction.JustReply("नमस्ते! अब मैं हिंदी में सहायता करूँगा।"))
            return
        }

        if (lowerText.contains("switch to english") || lowerText.contains("nimo switch to english") ||
            lowerText == "english" || lowerText.contains("english me baat")
        ) {
            LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
            callback.onResult(NimoAction.JustReply("Switched to English mode."))
            return
        }

        // 2. Local rule-based action match (Call, Open, Search, Capabilities, Greetings)
        val localAction = tryLocalFallback(userText)
        if (localAction != null) {
            Log.d(TAG, "Local action matched for: \"$userText\" -> ${localAction.javaClass.simpleName}")
            callback.onResult(localAction)
            return
        }

        val apiKey = BuildConfig.GROQ_API_KEY.trim()

        if (apiKey.isBlank()) {
            Log.w(TAG, "GROQ_API_KEY is missing or empty in local.properties.")
            val fallbackMsg = if (LanguageManager.isHindi()) {
                "नमस्ते! मैं निमो हूँ। मैं ऐप्स खोल सकता हूँ, फोन कॉल कर सकता हूँ और इंटरनेट पर खोज कर सकता हूँ।"
            } else {
                "I am Nimo! I can help you open apps like YouTube or WhatsApp, call contacts, search Google, or answer questions."
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
                    "मैं निमो हूँ! मैं ऐप्स खोल सकता हूँ, फोन कॉल कर सकता हूँ और सवाल जवाब कर सकता हूँ।"
                } else {
                    "I am Nimo! I can help you open apps like YouTube or WhatsApp, call contacts, or search Google."
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
                        "मैं निमो हूँ! मैं ऐप्स खोल सकता हूँ, फोन कॉल कर सकता हूँ और सवाल जवाब कर सकता हूँ।"
                    } else {
                        "I am Nimo! I can help you open apps like YouTube or WhatsApp, call contacts, or search Google."
                    }
                    callback.onResult(NimoAction.JustReply(fallbackMsg))
                    return
                }

                try {
                    val json = JSONObject(raw)
                    if (json.has("error")) {
                        val fallbackMsg = if (LanguageManager.isHindi()) {
                            "मैं निमो हूँ! मैं ऐप्स खोल सकता हूँ, फोन कॉल कर सकता हूँ।"
                        } else {
                            "I am Nimo! I can open apps, call contacts, or search the web."
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
                        "मैं निमो हूँ! मैं ऐप्स खोल सकता हूँ या फोन कॉल कर सकता हूँ।"
                    } else {
                        "I am Nimo! I can open apps, call contacts, or search Google for you."
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

    private fun tryLocalFallback(userText: String): NimoAction? {
        val lower = userText.lowercase().trim()
        val isHindi = LanguageManager.isHindi()

        // Strip wake word prefix if present
        val targetText = lower
            .replace("^\\b(nimo|neemo|nimmo|ok nimo|hey nimo|hello nimo|hi nimo|ok|hey|hello|hi)\\b".toRegex(), "")
            .trim()
            .ifBlank { lower }

        // 1. Call Contact
        if (targetText.startsWith("call ") || targetText.contains("ko call") || targetText.contains("ko phone")) {
            val contactName = targetText
                .removePrefix("call ")
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

        // 2. Open App
        if (targetText.startsWith("open ") || targetText.contains("kholo") || targetText.contains("chalao")) {
            val appName = targetText
                .removePrefix("open ")
                .replace("kholo", "")
                .replace("chalao", "")
                .trim()
            if (appName.isNotEmpty()) {
                val reply = if (isHindi) "आपके लिए $appName खोल रहा हूँ।" else "Opening $appName for you."
                return NimoAction.OpenApp(appName, reply)
            }
        }

        // 3. Search Web
        if (targetText.startsWith("search ") || targetText.startsWith("google ") || targetText.contains("khojo")) {
            val query = targetText
                .removePrefix("search ")
                .removePrefix("google ")
                .replace("khojo", "")
                .trim()
            if (query.isNotEmpty()) {
                val reply = if (isHindi) "$query खोज रहा हूँ।" else "Searching for $query."
                return NimoAction.SearchWeb(query, reply)
            }
        }

        // 4. Capability & Help Questions
        if (targetText.contains("what can you do") || targetText.contains("what all u can do") ||
            targetText.contains("what all you can do") || targetText.contains("what can u do") ||
            targetText.contains("what u can do") || targetText.contains("help") ||
            targetText.contains("who are you") || targetText.contains("features") ||
            targetText.contains("what do you do") || targetText.contains("kya kar sakte ho")
        ) {
            val reply = if (isHindi) {
                "मैं निमो हूँ! मैं आपके लिए यूट्यूब या व्हाट्सएप जैसे ऐप्स खोल सकता हूँ, फोन कॉल कर सकता हूँ और सवाल जवाब कर सकता हूँ।"
            } else {
                "I am Nimo! I can help you open apps like YouTube or WhatsApp, call contacts from your phonebook, search Google, or answer your questions."
            }
            return NimoAction.JustReply(reply)
        }

        // 5. Greetings
        if (targetText == "hi" || targetText == "hello" || targetText == "hey" || targetText == "namaste") {
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
                else -> JustReply(if (reply.isNotBlank()) reply else defaultReply)
            }
        }
    }
}
