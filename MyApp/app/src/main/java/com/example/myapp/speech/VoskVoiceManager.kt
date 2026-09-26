package com.example.myapp.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

object VoskVoiceManager {

    private const val TAG = "VoskVoiceManager"
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isModelLoading = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val WAKE_WORDS = listOf("hello nimo", "ok nimo", "hey nimo", "nimo", "neemo", "nimmo")

    enum class Mode {
        IDLE,
        WAKE_WORD,
        ACTIVE_QUERY
    }

    private var currentMode = Mode.IDLE
    private var wakeWordCallback: ((String?) -> Unit)? = null
    private var activeQueryResultCallback: ((String) -> Unit)? = null
    private var activeQueryErrorCallback: ((String) -> Unit)? = null
    private var activeQueryPartialCallback: ((String) -> Unit)? = null

    fun initModel(context: Context, onReady: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        if (model != null) {
            onReady?.invoke()
            return
        }

        if (isModelLoading) return
        isModelLoading = true

        val appContext = context.applicationContext
        executor.execute {
            try {
                val targetDir = File(appContext.filesDir, "model")
                val confFile = File(targetDir, "conf/model.conf")

                // If model is already extracted and valid, load directly
                if (targetDir.exists() && confFile.exists()) {
                    Log.d(TAG, "Vosk model found in storage, loading directly from ${targetDir.absolutePath}...")
                    val loadedModel = Model(targetDir.absolutePath)
                    model = loadedModel
                    isModelLoading = false
                    mainHandler.post { onReady?.invoke() }
                    return@execute
                }

                Log.d(TAG, "Unpacking Vosk model from assets/models/vosk-model-small-en-us-0.15...")
                StorageService.unpack(
                    appContext,
                    "models/vosk-model-small-en-us-0.15",
                    "model",
                    { loadedModel: Model ->
                        model = loadedModel
                        isModelLoading = false
                        Log.d(TAG, "Vosk model loaded successfully via StorageService!")
                        mainHandler.post { onReady?.invoke() }
                    },
                    { exception: IOException ->
                        Log.w(TAG, "StorageService unpack failed: ${exception.message}. Trying fallback asset copy...")
                        try {
                            copyAssetFolder(appContext, "models/vosk-model-small-en-us-0.15", targetDir)
                            val loadedModel = Model(targetDir.absolutePath)
                            model = loadedModel
                            isModelLoading = false
                            Log.d(TAG, "Vosk model loaded successfully via fallback asset copy!")
                            mainHandler.post { onReady?.invoke() }
                        } catch (e: Exception) {
                            isModelLoading = false
                            Log.e(TAG, "Fallback asset copy failed: ${e.message}", e)
                            mainHandler.post { onError?.invoke("Failed to load model: ${e.message}") }
                        }
                    }
                )
            } catch (e: Exception) {
                isModelLoading = false
                Log.e(TAG, "Failed to initialize Vosk model: ${e.message}", e)
                mainHandler.post { onError?.invoke("Failed to load model: ${e.message}") }
            }
        }
    }

    private fun copyAssetFolder(context: Context, srcName: String, dstDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(srcName) ?: return
        if (!dstDir.exists()) {
            dstDir.mkdirs()
        }
        for (filename in files) {
            val srcPath = if (srcName.isEmpty()) filename else "$srcName/$filename"
            val dstFile = File(dstDir, filename)
            val subFiles = assetManager.list(srcPath)
            if (!subFiles.isNullOrEmpty()) {
                copyAssetFolder(context, srcPath, dstFile)
            } else {
                assetManager.open(srcPath).use { inputStream ->
                    FileOutputStream(dstFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
    }

    fun startWakeWordListening(
        context: Context,
        onWakeWordDetected: (String?) -> Unit
    ) {
        wakeWordCallback = onWakeWordDetected
        currentMode = Mode.WAKE_WORD

        initModel(context, onReady = {
            startListeningInternal(context)
        }, onError = { err ->
            Log.e(TAG, "Wake word start failed: $err")
        })
    }

    fun startActiveQueryListening(
        context: Context,
        onPartial: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        activeQueryResultCallback = onResult
        activeQueryErrorCallback = onError
        activeQueryPartialCallback = onPartial
        currentMode = Mode.ACTIVE_QUERY

        initModel(context, onReady = {
            startListeningInternal(context)
        }, onError = { err ->
            onError(err)
        })
    }

    private fun startListeningInternal(context: Context) {
        val loadedModel = model ?: return
        stopSpeechService()

        try {
            val grammar = ContactVocabularySupplier.buildVocabularyJson(context)
            val recognizer = try {
                Recognizer(loadedModel, 16000.0f, grammar)
            } catch (e: Exception) {
                Recognizer(loadedModel, 16000.0f)
            }

            val service = SpeechService(recognizer, 16000.0f)
            speechService = service

            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (hypothesis.isNullOrEmpty()) return
                    if (TTSManager.isSpeaking()) return

                    val text = parseVoskJson(hypothesis, "partial")
                    if (text.isBlank()) return

                    Log.d(TAG, "Vosk Partial: $text")

                    // Wake word is confirmed only on onResult/onFinalResult (below),
                    // never on a partial. Vosk partials fire the instant "hey nimo"
                    // is heard, before the rest of a continuous phrase like "hey
                    // nimo open whatsapp" has been recognized - reacting here would
                    // silently drop "open whatsapp" every time it's said in one
                    // breath with the wake word.
                    if (currentMode == Mode.ACTIVE_QUERY) {
                        mainHandler.post {
                            activeQueryPartialCallback?.invoke(text)
                        }
                    }
                }

                override fun onResult(hypothesis: String?) {
                    if (hypothesis.isNullOrEmpty()) return
                    if (TTSManager.isSpeaking()) return

                    val text = parseVoskJson(hypothesis, "text")
                    if (text.isBlank()) return

                    Log.d(TAG, "Vosk Result: $text")

                    if (currentMode == Mode.WAKE_WORD) {
                        checkWakeWord(text)
                    } else if (currentMode == Mode.ACTIVE_QUERY) {
                        val callback = activeQueryResultCallback
                        stopListening()
                        mainHandler.post {
                            callback?.invoke(text)
                        }
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (hypothesis.isNullOrEmpty()) return
                    if (TTSManager.isSpeaking()) return

                    val text = parseVoskJson(hypothesis, "text")
                    if (text.isBlank()) return

                    Log.d(TAG, "Vosk FinalResult: $text")

                    if (currentMode == Mode.WAKE_WORD) {
                        checkWakeWord(text)
                    } else if (currentMode == Mode.ACTIVE_QUERY) {
                        val callback = activeQueryResultCallback
                        stopListening()
                        mainHandler.post {
                            callback?.invoke(text)
                        }
                    }
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "Vosk recognition error: ${exception?.message}")
                    if (currentMode == Mode.ACTIVE_QUERY) {
                        val errorCb = activeQueryErrorCallback
                        stopListening()
                        mainHandler.post {
                            errorCb?.invoke(exception?.message ?: "Voice recognition error")
                        }
                    }
                }

                override fun onTimeout() {
                    Log.d(TAG, "Vosk timeout")
                    if (currentMode == Mode.ACTIVE_QUERY) {
                        val errorCb = activeQueryErrorCallback
                        stopListening()
                        mainHandler.post {
                            errorCb?.invoke("Listening timed out")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechService: ${e.message}", e)
            if (currentMode == Mode.ACTIVE_QUERY) {
                activeQueryErrorCallback?.invoke("Failed to start voice listener: ${e.message}")
            }
        }
    }

    private fun checkWakeWord(text: String) {
        val lower = text.lowercase().trim()
        // Prefer the longest matching wake phrase (e.g. "hello nimo" over
        // just "nimo") so the remainder we slice off below is accurate.
        val matched = WAKE_WORDS
            .filter { lower.contains(it) }
            .maxByOrNull { it.length }
            ?: return

        Log.d(TAG, "Wake word detected in: \"$text\"")
        val idx = lower.indexOf(matched)
        val trailingCommand = if (idx >= 0) {
            lower.substring(idx + matched.length).trim().ifBlank { null }
        } else {
            null
        }

        val callback = wakeWordCallback
        stopListening()
        mainHandler.post {
            callback?.invoke(trailingCommand)
        }
    }

    private fun parseVoskJson(jsonStr: String, key: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            obj.optString(key, "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun stopSpeechService() {
        runCatching {
            speechService?.stop()
            speechService?.shutdown()
        }
        speechService = null
    }

    fun stopListening() {
        currentMode = Mode.IDLE
        stopSpeechService()
    }
}