package com.example.myapp.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapp.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cloud-based speech recognition using Groq's Whisper large-v3 model.
 * Much more accurate than Android's native SpeechRecognizer or a small
 * offline Vosk model for both English and Hindi/Hinglish speech, and
 * reuses the same GROQ_API_KEY already configured for Nimo's LLM calls.
 *
 * Records raw PCM audio locally with basic silence detection to know when
 * the user has stopped speaking, then uploads the captured clip as a WAV
 * file to Groq's /audio/transcriptions endpoint.
 */
object GroqWhisperManager {

    private const val TAG = "GroqWhisperManager"
    private const val TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"

    private const val SAMPLE_RATE = 16000
    private const val MAX_RECORD_MS = 15_000L
    private const val SILENCE_HANG_MS = 900L
    private const val MIN_SPEECH_MS = 400L
    private const val NOISE_CALIBRATION_MS = 250L
    private const val MIN_SILENCE_THRESHOLD = 350.0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var isRecording = false
    @Volatile private var audioRecord: AudioRecord? = null

    /**
     * Starts recording from the mic, auto-stops when the user goes quiet
     * (or after MAX_RECORD_MS), then uploads the clip to Groq Whisper and
     * returns the recognized text.
     *
     * onPartial is invoked once with a "Listening..." style status only,
     * since Whisper does not provide true streaming partial results.
     */
    fun startActiveQueryListening(
        context: Context,
        onPartial: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Microphone permission not granted")
            return
        }

        if (BuildConfig.GROQ_API_KEY.isBlank()) {
            onError("Missing Groq API key")
            return
        }

        if (isRecording) {
            stopListening()
        }

        executor.execute {
            recordAndTranscribe(appContext, onPartial, onResult, onError)
        }
    }

    fun stopListening() {
        isRecording = false
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }

    private fun recordAndTranscribe(
        context: Context,
        onPartial: ((String) -> Unit)?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            mainHandler.post { onError("Audio recording not supported on this device") }
            return
        }

        val bufferSize = minBufSize * 2
        val record: AudioRecord
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            mainHandler.post { onError("Microphone permission not granted") }
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { onError("Failed to initialize microphone") }
            return
        }

        audioRecord = record
        isRecording = true

        mainHandler.post { onPartial?.invoke("Listening...") }

        val pcmBuffer = ByteArrayOutputStream()
        val readBuf = ShortArray(bufferSize / 2)

        // Energy-based voice activity detection, calibrated to the room's
        // ambient noise instead of a fixed threshold. A fixed threshold is
        // either too sensitive (a noisy room never reads as "silence", so
        // recognition drags on or captures mostly noise) or too strict (soft
        // speech in a quiet room gets missed) - measuring the actual noise
        // floor for the first stretch of audio fixes both for Hindi and
        // English alike, since this operates purely on signal level.
        var noiseFloorSum = 0.0
        var noiseFloorSamples = 0
        var silenceThreshold = MIN_SILENCE_THRESHOLD
        var calibrated = false

        var hasHeardSpeech = false
        var speechStartedAt = 0L
        var lastLoudAt = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()

        try {
            record.startRecording()

            while (isRecording) {
                val read = record.read(readBuf, 0, readBuf.size)
                if (read <= 0) continue

                var sumSquares = 0.0
                for (i in 0 until read) {
                    val sample = readBuf[i].toDouble()
                    sumSquares += sample * sample
                }
                val rms = sqrt(sumSquares / read)

                // Append PCM16 little-endian bytes to buffer
                for (i in 0 until read) {
                    val v = readBuf[i].toInt()
                    pcmBuffer.write(v and 0xFF)
                    pcmBuffer.write((v shr 8) and 0xFF)
                }

                val now = System.currentTimeMillis()
                val elapsed = now - startedAt

                if (!calibrated) {
                    // Collect ambient noise level during a brief warm-up
                    // window before making any speech/silence decisions.
                    noiseFloorSum += rms
                    noiseFloorSamples++
                    if (elapsed >= NOISE_CALIBRATION_MS) {
                        val avgNoise = if (noiseFloorSamples > 0) noiseFloorSum / noiseFloorSamples else 0.0
                        // Comfortably above the measured noise floor, but
                        // never below a sane minimum for near-silent rooms.
                        silenceThreshold = maxOf(MIN_SILENCE_THRESHOLD, avgNoise * 2.2)
                        calibrated = true
                        lastLoudAt = now
                    }
                } else if (rms > silenceThreshold) {
                    lastLoudAt = now
                    if (!hasHeardSpeech) {
                        hasHeardSpeech = true
                        speechStartedAt = now
                    }
                }

                val silenceElapsed = now - lastLoudAt

                if (elapsed >= MAX_RECORD_MS) break
                if (calibrated && hasHeardSpeech &&
                    (now - speechStartedAt) >= MIN_SPEECH_MS &&
                    silenceElapsed >= SILENCE_HANG_MS
                ) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
            stopListening()
            mainHandler.post { onError("Recording error: ${e.message}") }
            return
        }

        stopListening()

        if (!hasHeardSpeech) {
            mainHandler.post { onError("Speech not recognized") }
            return
        }

        val pcmBytes = pcmBuffer.toByteArray()
        if (pcmBytes.isEmpty()) {
            mainHandler.post { onError("Speech not recognized") }
            return
        }

        val wavFile: File
        try {
            wavFile = writeWavFile(context, pcmBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV file: ${e.message}", e)
            mainHandler.post { onError("Failed to prepare audio: ${e.message}") }
            return
        }

        uploadForTranscription(wavFile, onResult, onError)
    }

    private fun writeWavFile(context: Context, pcmData: ByteArray): File {
        val outFile = File(context.cacheDir, "nimo_query_${System.currentTimeMillis()}.wav")
        FileOutputStream(outFile).use { out ->
            val totalDataLen = pcmData.size + 36
            val byteRate = SAMPLE_RATE * 2 // mono, 16-bit

            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            writeIntLE(header, 4, totalDataLen)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeIntLE(header, 16, 16) // Subchunk1Size for PCM
            writeShortLE(header, 20, 1) // AudioFormat PCM
            writeShortLE(header, 22, 1) // NumChannels mono
            writeIntLE(header, 24, SAMPLE_RATE)
            writeIntLE(header, 28, byteRate)
            writeShortLE(header, 32, 2) // BlockAlign
            writeShortLE(header, 34, 16) // BitsPerSample
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            writeIntLE(header, 40, pcmData.size)

            out.write(header)
            out.write(pcmData)
        }
        return outFile
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun uploadForTranscription(
        wavFile: File,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = BuildConfig.GROQ_API_KEY.trim()
        val languageCode = if (LanguageManager.isHindi()) "hi" else "en"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", languageCode)
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(TRANSCRIBE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                wavFile.delete()
                Log.e(TAG, "Transcription request failed: ${e.message}", e)
                mainHandler.post { onError("Network error: ${e.message}") }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    wavFile.delete()
                    val bodyStr = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        Log.e(TAG, "Transcription failed (${it.code}): $bodyStr")
                        mainHandler.post { onError("Transcription failed (${it.code})") }
                        return
                    }
                    try {
                        val json = JSONObject(bodyStr)
                        val text = json.optString("text", "").trim()
                        if (text.isBlank()) {
                            mainHandler.post { onError("Speech not recognized") }
                        } else {
                            mainHandler.post { onResult(text) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse transcription response: ${e.message}", e)
                        mainHandler.post { onError("Failed to parse response") }
                    }
                }
            }
        })
    }
}