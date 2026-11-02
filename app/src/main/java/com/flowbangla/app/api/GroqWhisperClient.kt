package com.flowbangla.app.api

import android.util.Log
import com.flowbangla.app.audio.AudioFileInspector
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

data class GroqWhisperResult(
    val text: String,
    val modelId: String,
    val latencyMs: Long
)

class GroqWhisperClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "GroqWhisperClient"
        private const val GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL_LARGE_V3 = "whisper-large-v3"
        private const val MODEL_TURBO = "whisper-large-v3-turbo"
    }

    suspend fun transcribeAudio(
        audioFile: File,
        apiKey: String,
        customVocabulary: List<String> = emptyList()
    ): GroqWhisperResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            throw FlowApiException(400, ErrorCategory.AUTHENTICATION_FAILED, "Groq API key is empty.")
        }

        val meta = AudioFileInspector.inspectAndValidate(audioFile)
        val models = listOf(MODEL_LARGE_V3, MODEL_TURBO)
        var lastException: FlowApiException? = null

        val promptHint = if (customVocabulary.isNotEmpty()) {
            customVocabulary.take(100).joinToString(", ")
        } else null

        for (model in models) {
            val startMs = System.currentTimeMillis()
            try {
                val fileBody = audioFile.asRequestBody(meta.verifiedMimeType.toMediaType())
                val multipartBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, fileBody)
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0.0")

                if (!promptHint.isNullOrBlank()) {
                    multipartBuilder.addFormDataPart("prompt", promptHint)
                }

                val request = Request.Builder()
                    .url(GROQ_TRANSCRIPTION_URL)
                    .header("Authorization", "Bearer $cleanKey")
                    .post(multipartBuilder.build())
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val json = JSONObject(body)
                        val text = json.optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            val latency = System.currentTimeMillis() - startMs
                            Log.i(TAG, "Groq transcription succeeded with $model in ${latency}ms")
                            return@withContext GroqWhisperResult(text, model, latency)
                        } else {
                            throw FlowApiException(200, ErrorCategory.PROVIDER_RETURNED_NO_TRANSCRIPT, "Groq returned empty text")
                        }
                    } else {
                        val cat = when (response.code) {
                            401, 403 -> ErrorCategory.AUTHENTICATION_FAILED
                            429 -> ErrorCategory.QUOTA_EXCEEDED
                            in 500..599 -> ErrorCategory.PROVIDER_SERVER_ERROR
                            else -> ErrorCategory.REQUEST_REJECTED
                        }
                        val errMsg = try {
                            JSONObject(body).optJSONObject("error")?.optString("message", "") ?: body.take(120)
                        } catch (_: Exception) { body.take(120) }

                        lastException = FlowApiException(response.code, cat, errMsg)
                        Log.w(TAG, "Groq model $model failed: HTTP ${response.code} $errMsg")
                    }
                }
            } catch (e: Exception) {
                if (e is FlowApiException) {
                    lastException = e
                } else {
                    lastException = FlowApiException(0, ErrorCategory.NETWORK_TIMEOUT, e.message ?: "Network error")
                }
                Log.w(TAG, "Groq attempt for $model encountered error: ${e.message}")
            }
        }

        throw lastException ?: FlowApiException(500, ErrorCategory.PROVIDER_SERVER_ERROR, "Groq transcription failed.")
    }
}
