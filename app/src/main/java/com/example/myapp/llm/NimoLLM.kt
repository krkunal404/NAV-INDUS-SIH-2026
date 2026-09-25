package com.example.myapp.llm

import com.example.myapp.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Sends the user's query to an LLM and asks it to respond with a small
 * JSON "action" object instead of free text — this makes the response
 * trivially easy to parse and act on (open an app, place a call, etc.)
 * regardless of which LLM provider you're using.
 *
 * Currently wired to Groq (free, no billing setup). To switch providers,
 * only BASE_URL / MODEL / auth header need to change — the rest of the
 * app (ActionExecutor, MainActivity) is provider-agnostic.
 */
object NimoLLM {

    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.3-70b-versatile"

    private val client = OkHttpClient()

    private const val SYSTEM_PROMPT = """
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

    interface Callback {
        fun onResult(action: NimoAction)
        fun onError(message: String)
    }

    fun ask(userText: String, callback: Callback) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
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
            .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val raw = response.body?.string()
                if (raw.isNullOrBlank()) {
                    callback.onError("Empty response from LLM")
                    return
                }
                try {
                    val json = JSONObject(raw)
                    val content = json
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    val actionJson = JSONObject(content)
                    callback.onResult(NimoAction.fromJson(actionJson))
                } catch (e: Exception) {
                    callback.onError("Failed to parse LLM response: ${e.message}")
                }
            }
        })
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
            val reply = json.optString("reply", "")
            return when (json.optString("action")) {
                "open_app" -> OpenApp(json.optString("app_name"), reply)
                "search_web" -> SearchWeb(json.optString("query"), reply)
                "call_contact" -> CallContact(json.optString("contact_name"), reply)
                else -> JustReply(reply)
            }
        }
    }
}
