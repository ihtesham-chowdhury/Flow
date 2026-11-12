package com.flowbangla.app.format

import android.util.Log
import com.flowbangla.app.api.GeminiInteractionsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud text formatter using Google Gemini via Interactions API (or generateContent fallback).
 */
class GeminiTextFormatter(
    private val apiKeyProvider: () -> String
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val interactionsAdapter = GeminiInteractionsAdapter(httpClient)

    companion object {
        private const val TAG = "GeminiTextFormatter"
        private val CANDIDATES = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.1-flash-lite")
    }

    suspend fun formatText(
        rawTranscript: String,
        plan: FormatPlan
    ): FormattedEnvelope? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext null

        kotlinx.coroutines.withTimeoutOrNull(1_800L) {
            val systemPrompt = PromptBuilder.buildSystemPrompt(
                numeralPreference = plan.numeralPreference,
                outputScript = plan.outputScript,
                explicitMode = if (plan.isAutoDetected) FormattingMode.AUTO else FormattingMode.fromId(plan.documentType.id),
                customVocabulary = plan.protectedTokens,
                cleanupLevel = plan.cleanupLevel,
                appCategory = plan.appCategory
            )
            val userPrompt = PromptBuilder.buildUserMessage(rawTranscript)

            // 1. Try Interactions API first (official route in Blueprint)
            try {
                val result = interactionsAdapter.formatTranscript(
                    systemInstruction = systemPrompt,
                    userEnvelope = userPrompt,
                    rawApiKey = apiKey
                )
                if (result.text.isNotBlank()) {
                    return@withTimeoutOrNull FormatterResponseParser.parse(result.text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Interactions API formatting attempt: ${e.message}")
            }

            // 2. Fallback to generateContent API across Gemini candidate models
            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("maxOutputTokens", (rawTranscript.length * 3 + 128).coerceIn(256, 1024))
                    put("responseMimeType", "application/json")
                })
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            for (model in CANDIDATES) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder().url(url).post(requestBody).build()
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string().orEmpty()
                            val json = JSONObject(bodyStr)
                            val text = json.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text")

                            if (!text.isNullOrBlank()) {
                                return@withTimeoutOrNull FormatterResponseParser.parse(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$model generateContent attempt error: ${e.message}")
                }
            }
            null
        }
    }
}
