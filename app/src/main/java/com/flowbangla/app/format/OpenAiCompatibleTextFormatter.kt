package com.flowbangla.app.format

import android.util.Log
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
 * Cloud text formatter using OpenAI-compatible APIs (Groq, Cerebras, OpenRouter).
 */
class OpenAiCompatibleTextFormatter(
    private val endpointUrl: String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "OpenAiTextFormatter"
    }

    suspend fun formatText(
        rawTranscript: String,
        plan: FormatPlan
    ): FormattedEnvelope? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        val model = modelProvider().trim()
        if (apiKey.isBlank() || model.isBlank()) return@withContext null

        val systemPrompt = PromptBuilder.buildSystemPrompt(
            numeralPreference = plan.numeralPreference,
            outputScript = plan.outputScript,
            explicitMode = if (plan.isAutoDetected) FormattingMode.AUTO else FormattingMode.fromId(plan.documentType.id),
            customVocabulary = plan.protectedTokens,
            cleanupLevel = plan.cleanupLevel,
            appCategory = plan.appCategory
        )
        val userPrompt = PromptBuilder.buildUserMessage(rawTranscript)

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 2048)
            put("response_format", JSONObject().put("type", "json_object"))
        }

        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return@withContext try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val content = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")

                    if (!content.isNullOrBlank()) {
                        FormatterResponseParser.parse(content)
                    } else null
                } else {
                    Log.w(TAG, "OpenAI-compatible request failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI-compatible formatting error: ${e.message}")
            null
        }
    }
}
