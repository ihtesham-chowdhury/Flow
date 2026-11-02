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

class GeminiApiClient(
    private val apiKeyProvider: () -> String,
    private val isSmartPolishEnabled: () -> Boolean
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GeminiApiClient"
        private val API_VERSIONS = listOf("v1beta", "v1")
        private val STATIC_FALLBACK_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3.1-flash-lite"
        )

        // In-memory cached working endpoint to avoid searching on every single request
        @Volatile
        private var cachedWorkingEndpoint: Pair<String, String>? = null // Pair(apiVersion, modelName)

        private const val PROMPT_SMART_POLISH =
            """Role: You are an expert voice dictation editor and transcriber (Wispr Flow replacement).
Task: Convert the provided audio into clean, polished, and beautifully written text.

Strict Rules:
1. Language Detection:
   - Automatically detect whether the speaker is speaking in Bengali, English, or a natural mix of both (Banglish).
   - Transcribe in the exact language(s) spoken. Do NOT translate from one language to another.
2. Punctuation & Formatting:
   - If speaking English: Apply proper English punctuation (periods '.', commas ',', question marks '?', apostrophes, quotes) and capitalize the first letter of sentences and proper nouns.
   - If speaking Bengali: Apply proper Bengali punctuation (দাঁড়ি '।', কমা ',', প্রশ্নবোধক '?', quotes '“”').
   - If code-switching (mixed): Apply appropriate punctuation matching the clause structure naturally.
3. Smart Polish & Filler Removal:
   - Remove verbal fillers and hesitation sounds:
     * English fillers: "um", "uh", "like", "you know", "er", "ah", "so yeah".
     * Bengali fillers: "উম", "আহ", "মানে", "মানে হচ্ছে", "ইয়ে".
   - Clean up accidental stutters and false starts while strictly preserving the speaker's meaning and vocabulary choices.
4. Output Format:
   - Output ONLY the final transcribed and polished text.
   - Do NOT include explanations, preamble, markdown formatting (like ```text), or notes."""

        private const val PROMPT_LITERAL_PUNCTUATION =
            """Role: Accurate voice transcriber (Wispr Flow replacement).
Task: Transcribe the provided audio word-for-word in English or Bengali script as spoken.
Rules:
1. Automatically detect the language (English, Bengali, or mixed). Do not translate.
2. Preserve every spoken word exactly as said.
3. Add natural punctuation (periods/daris, commas, question marks).
4. Output ONLY the transcribed text."""
    }

    suspend fun transcribeAndPolish(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key is not configured. Please set your key in Flow."))
        }

        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val promptText = if (isSmartPolishEnabled()) {
                PROMPT_SMART_POLISH
            } else {
                PROMPT_LITERAL_PUNCTUATION
            }

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray()
                val userContent = JSONObject().apply {
                    put("role", "user")
                    val partsArray = JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                        put(JSONObject().apply {
                            val inlineData = JSONObject().apply {
                                put("mimeType", "audio/mp4")
                                put("data", base64Audio)
                            }
                            put("inlineData", inlineData)
                        })
                    }
                    put("parts", partsArray)
                }
                contentsArray.put(userContent)
                put("contents", contentsArray)

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.15)
                    put("maxOutputTokens", 2048)
                })
            }

            val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            // 1. If we have a cached working endpoint, try it first
            cachedWorkingEndpoint?.let { (ver, model) ->
                val result = executeGeneration(ver, model, apiKey, body)
                if (result.isSuccess) {
                    return@withContext result
                }
                Log.w(TAG, "Cached endpoint ($ver/$model) failed, rediscovering...")
                cachedWorkingEndpoint = null
            }

            // 2. Discover available models from the Google API for this user's key
            val discovered = querySupportedModels(apiKey)
            val candidateModels = if (discovered.isNotEmpty()) {
                (discovered + STATIC_FALLBACK_MODELS).distinct()
            } else {
                STATIC_FALLBACK_MODELS
            }

            var lastErrorMsg = "Unable to connect to Gemini API."

            for (ver in API_VERSIONS) {
                for (model in candidateModels) {
                    val url = "https://generativelanguage.googleapis.com/$ver/models/$model:generateContent?key=$apiKey"
                    val request = Request.Builder().url(url).post(body).build()

                    try {
                        val response = client.newCall(request).execute()
                        val responseBody = response.body?.string()

                        if (response.isSuccessful) {
                            val parsedText = parseTranscriptionResponse(responseBody)
                            if (parsedText.isNotBlank()) {
                                cachedWorkingEndpoint = Pair(ver, model)
                                Log.i(TAG, "Gemini transcription succeeded using $ver/$model")
                                return@withContext Result.success(parsedText)
                            } else {
                                return@withContext Result.failure(Exception("কোন কথা শনাক্ত করা যায়নি (No speech detected)."))
                            }
                        }

                        val errorMsg = parseErrorMessage(response.code, responseBody)
                        lastErrorMsg = errorMsg

                        // If 404, model not on this version/endpoint, try next
                        if (response.code == 404) {
                            Log.w(TAG, "$ver/models/$model returned 404, checking next candidate...")
                            continue
                        } else {
                            // Non-404 error (e.g. 400 bad key, 403 quota/permissions) should report immediately
                            return@withContext Result.failure(Exception(errorMsg))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Request to $ver/$model failed: ${e.message}")
                        lastErrorMsg = e.message ?: lastErrorMsg
                    }
                }
            }

            Result.failure(Exception(lastErrorMsg))
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun executeGeneration(
        apiVersion: String,
        modelName: String,
        apiKey: String,
        body: okhttp3.RequestBody
    ): Result<String> {
        val url = "https://generativelanguage.googleapis.com/$apiVersion/models/$modelName:generateContent?key=$apiKey"
        val request = Request.Builder().url(url).post(body).build()
        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful) {
                val parsedText = parseTranscriptionResponse(responseBody)
                if (parsedText.isNotBlank()) {
                    Result.success(parsedText)
                } else {
                    Result.failure(Exception("No speech detected."))
                }
            } else {
                Result.failure(Exception(parseErrorMessage(response.code, responseBody)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun querySupportedModels(apiKey: String): List<String> {
        for (ver in API_VERSIONS) {
            val url = "https://generativelanguage.googleapis.com/$ver/models?key=$apiKey"
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val modelsArray = json.optJSONArray("models") ?: return@use
                        val list = mutableListOf<String>()
                        for (i in 0 until modelsArray.length()) {
                            val m = modelsArray.optJSONObject(i) ?: continue
                            val name = m.optString("name", "").removePrefix("models/")
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            val supportsGenerate = methods != null && (0 until methods.length()).any {
                                methods.optString(it) == "generateContent"
                            }
                            if (supportsGenerate && name.isNotBlank()) {
                                list.add(name)
                            }
                        }
                        if (list.isNotEmpty()) {
                            // Prioritize flash models
                            return list.sortedByDescending { it.contains("flash") }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ListModels failed on $ver: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseTranscriptionResponse(jsonStr: String?): String {
        if (jsonStr == null) return ""
        val root = JSONObject(jsonStr)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        if (parts.length() == 0) return ""

        val rawText = parts.getJSONObject(0).optString("text", "").trim()
        return rawText
            .removePrefix("```text")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseErrorMessage(code: Int, body: String?): String {
        val serverMessage = try {
            if (!body.isNullOrBlank()) {
                val json = JSONObject(body)
                json.optJSONObject("error")?.optString("message", "") ?: ""
            } else ""
        } catch (_: Exception) { "" }

        return when (code) {
            400 -> if (serverMessage.isNotBlank()) "API request error: $serverMessage" else "API request error (Check your Gemini API Key) / ভুল API Key।"
            401, 403 -> if (serverMessage.isNotBlank()) "Unauthorized: $serverMessage" else "API Key unauthorized. Please check your key at Google AI Studio."
            404 -> if (serverMessage.isNotBlank()) "Model not found (404): $serverMessage" else "Gemini model not found (404)."
            429 -> "Rate limit reached (15 req/min). Please wait a moment and try again."
            500, 503 -> "Google Gemini server is busy. Please try again."
            else -> if (serverMessage.isNotBlank()) "Server error ($code): $serverMessage" else "Server error ($code)"
        }
    }
}
