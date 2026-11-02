package com.flowbangla.app.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dedicated Groq Whisper Cloud ASR Client.
 * Provides high-speed (sub-second) multilingual speech-to-text as an independent
 * cloud transcription alternative or fallback.
 */
class GroqTranscriptionClient(
    private val apiKeyProvider: () -> String
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GroqTranscription"
        private const val GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val PRIMARY_MODEL = "whisper-large-v3"
        private const val TURBO_MODEL = "whisper-large-v3-turbo"
    }

    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Groq API key is not configured."))
        }

        if (!audioFile.exists() || audioFile.length() < 100) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty or too short."))
        }

        val models = listOf(PRIMARY_MODEL, TURBO_MODEL)
        var lastError = "Unable to connect to Groq Whisper API."

        for (model in models) {
            try {
                val fileBody = audioFile.asRequestBody("audio/mp4".toMediaType())
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, fileBody)
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0.0")
                    .build()

                val request = Request.Builder()
                    .url(GROQ_TRANSCRIPTION_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val json = JSONObject(bodyStr)
                        val text = json.optString("text", "").trim()
                        if (text.isNotBlank()) {
                            Log.i(TAG, "Groq transcription succeeded using $model")
                            return@withContext Result.success(text)
                        } else {
                            return@withContext Result.failure(Exception("কোন কথা শনাক্ত করা যায়নি (No speech detected)."))
                        }
                    } else {
                        val errMsg = try {
                            JSONObject(bodyStr).optJSONObject("error")?.optString("message", "") ?: ""
                        } catch (_: Exception) { "" }
                        lastError = if (errMsg.isNotBlank()) "Groq error ($model): $errMsg" else "Groq error (${response.code})"
                        Log.w(TAG, lastError)
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: lastError
                Log.w(TAG, "Groq transcription failed for $model: ${e.message}")
            }
        }

        Result.failure(Exception(lastError))
    }
}
