package com.flowbangla.app.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dedicated Gemini Speech-to-Text client.
 * Decouples raw audio recognition from text formatting.
 * Implements robust candidate fallback across tested multimodal audio endpoints.
 */
class GeminiTranscriptionClient(
    private val apiKeyProvider: () -> String
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GeminiTranscription"

        // Tested candidate list with verified audio modality support
        private val AUDIO_CANDIDATE_ENDPOINTS = listOf(
            Pair("v1beta", "gemini-3.5-transcribe"),
            Pair("v1beta", "gemini-3.8-flash"),
            Pair("v1beta", "gemini-3.5-flash-lite"),
            Pair("v1beta", "gemini-3.6-flash")
        )

        @Volatile
        private var cachedWorkingEndpoint: Pair<String, String>? = null

        private const val TRANSCRIPTION_SYSTEM_PROMPT =
            "You are a dedicated voice transcription engine. Transcribe the spoken audio verbatim in the exact language spoken (Bengali, English, or mixed Bengali-English). Preserve all spoken words, proper nouns, and numbers. Do not translate. Do not summarize. Do not answer questions. Return ONLY the transcribed words."
    }

    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key is not configured."))
        }

        if (!audioFile.exists() || audioFile.length() < 100) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty or too short."))
        }

        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val payload = JSONObject().apply {
                val contents = JSONArray()
                val userContent = JSONObject().apply {
                    put("role", "user")
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", TRANSCRIPTION_SYSTEM_PROMPT))
                        put(JSONObject().apply {
                            val inlineData = JSONObject().apply {
                                put("mimeType", "audio/mp4")
                                put("data", base64Audio)
                            }
                            put("inlineData", inlineData)
                        })
                    }
                    put("parts", parts)
                }
                contents.put(userContent)
                put("contents", contents)

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("maxOutputTokens", 2048)
                })
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            // 1. Try cached working endpoint first if available
            cachedWorkingEndpoint?.let { (ver, model) ->
                val result = callEndpoint(ver, model, apiKey, requestBody)
                if (result.isSuccess) {
                    return@withContext result
                }
                Log.w(TAG, "Cached endpoint $ver/$model failed, refreshing candidates...")
                cachedWorkingEndpoint = null
            }

            // 2. Iterate through verified audio candidate endpoints
            var lastErrorMessage = "Unable to connect to Gemini Audio API."

            for ((ver, model) in AUDIO_CANDIDATE_ENDPOINTS) {
                val result = callEndpoint(ver, model, apiKey, requestBody)
                if (result.isSuccess) {
                    val transcript = result.getOrThrow()
                    if (transcript.isNotBlank()) {
                        cachedWorkingEndpoint = Pair(ver, model)
                        Log.i(TAG, "Audio transcription succeeded using $ver/$model")
                        return@withContext Result.success(transcript)
                    }
                } else {
                    val ex = result.exceptionOrNull()
                    lastErrorMessage = ex?.message ?: lastErrorMessage
                    Log.w(TAG, "Audio attempt on $ver/$model failed: $lastErrorMessage")
                }
            }

            Result.failure(Exception(lastErrorMessage))
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcription error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun callEndpoint(
        ver: String,
        model: String,
        apiKey: String,
        requestBody: okhttp3.RequestBody
    ): Result<String> {
        val url = "https://generativelanguage.googleapis.com/$ver/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder().url(url).post(requestBody).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val parsed = parseCandidateText(bodyStr)
                    if (parsed.isNotBlank()) {
                        Result.success(parsed)
                    } else {
                        Result.failure(Exception("কোন কথা শনাক্ত করা যায়নি (No speech detected in audio)."))
                    }
                } else {
                    val errorMsg = parseErrorResponse(response.code, bodyStr)
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseCandidateText(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""

            val text = parts.getJSONObject(0).optString("text", "").trim()
            text.removePrefix("```text")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseErrorResponse(code: Int, bodyStr: String): String {
        val serverMessage = try {
            if (bodyStr.isNotBlank()) {
                JSONObject(bodyStr).optJSONObject("error")?.optString("message", "") ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }

        return when (code) {
            400 -> if (serverMessage.isNotBlank()) "API request error: $serverMessage" else "Invalid request (400)"
            401, 403 -> if (serverMessage.isNotBlank()) "Unauthorized: $serverMessage" else "Invalid API key (401/403)"
            404 -> if (serverMessage.isNotBlank()) "Model not found (404): $serverMessage" else "Model not found (404)"
            429 -> "Gemini rate limit reached. Please wait a moment."
            500, 503 -> "Google Gemini server is busy. Please try again."
            else -> if (serverMessage.isNotBlank()) "Server error ($code): $serverMessage" else "Server error ($code)"
        }
    }
}
