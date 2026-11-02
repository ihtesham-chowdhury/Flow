package com.flowbangla.app.api

import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.CloudPolishProvider
import com.flowbangla.app.polish.LocalFillerCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudTextPolishClient(
    private val prefs: PreferencesManager
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val TEXT_POLISH_SYSTEM_PROMPT = """
Role: You are an expert voice dictation editor and formatter (Wispr Flow replacement) for Bengali, English, and Banglish (mixed Bengali-English).
Task: Polish and punctuate the raw speech-to-text transcript provided by the user.

Strict Rules:
1. Language Preservation:
   - Keep the exact language(s) and script(s) present in the input. Do NOT translate Bengali to English or English to Bengali.
2. Punctuation & Capitalization:
   - For Bengali clauses: Add proper Bengali punctuation (দাঁড়ি '।', কমা ',', প্রশ্নবোধক '?').
   - For English clauses: Add proper English punctuation ('.', ',', '?', '!') and capitalize sentence starts and proper nouns.
3. Smart Filler & Stutter Removal:
   - Remove verbal fillers ("উম", "আহ", "মানে", "মানে হচ্ছে", "ইয়ে", "um", "uh", "like", "you know", "er") and accidental word repetitions.
   - Fix obvious phonetic ASR slips in Banglish while strictly preserving the speaker's original wording and intent.
4. Output Format:
   - Output ONLY the final polished text. Never add quotes, explanations, or preamble.
"""
    }

    suspend fun polishText(rawTranscript: String): String = withContext(Dispatchers.IO) {
        if (rawTranscript.isBlank()) return@withContext ""

        val provider = prefs.cloudPolishProvider
        return@withContext try {
            when (provider) {
                CloudPolishProvider.GEMINI -> callGeminiTextApi(rawTranscript)
                CloudPolishProvider.GROQ -> callOpenAiCompatibleApi(
                    endpoint = CloudPolishProvider.GROQ.endpointUrl,
                    apiKey = prefs.groqApiKey,
                    model = prefs.groqModelName.ifBlank { CloudPolishProvider.GROQ.defaultModel },
                    rawText = rawTranscript
                )
                CloudPolishProvider.CEREBRAS -> callOpenAiCompatibleApi(
                    endpoint = CloudPolishProvider.CEREBRAS.endpointUrl,
                    apiKey = prefs.cerebrasApiKey,
                    model = CloudPolishProvider.CEREBRAS.defaultModel,
                    rawText = rawTranscript
                )
                CloudPolishProvider.OPENROUTER -> callOpenAiCompatibleApi(
                    endpoint = CloudPolishProvider.OPENROUTER.endpointUrl,
                    apiKey = prefs.openRouterApiKey,
                    model = CloudPolishProvider.OPENROUTER.defaultModel,
                    rawText = rawTranscript
                )
            }
        } catch (_: Exception) {
            // Instant zero-data-loss fallback if offline or rate-limited
            LocalFillerCleaner.cleanAndPunctuate(rawTranscript)
        }
    }

    private fun callGeminiTextApi(rawText: String): String {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) return LocalFillerCleaner.cleanAndPunctuate(rawText)

        val candidateModels = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.1-flash-lite")
        val apiVersions = listOf("v1beta", "v1")

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", TEXT_POLISH_SYSTEM_PROMPT.trim())))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", rawText)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
            })
        }
        val requestBody = payload.toString().toRequestBody(jsonMediaType)

        for (ver in apiVersions) {
            for (model in candidateModels) {
                val url = "https://generativelanguage.googleapis.com/$ver/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder().url(url).post(requestBody).build()
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string().orEmpty()
                            val json = JSONObject(bodyStr)
                            val result = json.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                ?.trim()

                            if (!result.isNullOrEmpty()) return result
                        }
                    }
                } catch (_: Exception) {
                    // Try next endpoint
                }
            }
        }
        return LocalFillerCleaner.cleanAndPunctuate(rawText)
    }

    private fun callOpenAiCompatibleApi(
        endpoint: String,
        apiKey: String,
        model: String,
        rawText: String
    ): String {
        if (apiKey.isBlank()) return LocalFillerCleaner.cleanAndPunctuate(rawText)

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", TEXT_POLISH_SYSTEM_PROMPT.trim()))
            .put(JSONObject().put("role", "user").put("content", rawText))

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 1024)
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))

        if (endpoint.contains("openrouter.ai")) {
            requestBuilder.addHeader("HTTP-Referer", "https://github.com/flowbangla/app")
            requestBuilder.addHeader("X-Title", "Flow Voice Dictation")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                return LocalFillerCleaner.cleanAndPunctuate(rawText)
            }
            val bodyStr = response.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val result = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()

            return if (!result.isNullOrEmpty()) result else LocalFillerCleaner.cleanAndPunctuate(rawText)
        }
    }
}
