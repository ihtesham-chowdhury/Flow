package com.flowbangla.app.api

import android.util.Base64
import android.util.Log
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.data.ProviderId
import com.flowbangla.app.data.ProviderRoutingMode
import com.flowbangla.app.format.RecognitionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AsrExecutionResult(
    val transcript: String,
    val source: RecognitionSource,
    val alreadySmartFormatted: Boolean
)

class SmartProviderRouter(
    private val http: OkHttpClient,
    private val prefs: PreferencesManager,
    private val geminiAdapter: GeminiInteractionsAdapter,
    private val groqWhisperClient: GroqWhisperClient
) {
    companion object {
        private const val TAG = "FlowSmartRouter"
        const val SHORT_AUDIO_THRESHOLD_SEC = 20

        fun computeAsrProviderOrder(
            routingMode: ProviderRoutingMode,
            audioDurationSeconds: Int,
            isGeminiEnabled: Boolean,
            geminiApiKey: String,
            isGroqEnabled: Boolean,
            groqApiKey: String,
            isOpenRouterEnabled: Boolean,
            openRouterApiKey: String
        ): List<ProviderId> {
            val geminiReady = isGeminiEnabled && geminiApiKey.trim().isNotEmpty()
            val groqReady = isGroqEnabled && groqApiKey.trim().isNotEmpty()
            val openRouterReady = isOpenRouterEnabled && openRouterApiKey.trim().isNotEmpty()

            val order = mutableListOf<ProviderId>()

            if (routingMode == ProviderRoutingMode.SMART_AUTO && geminiReady && groqReady) {
                if (audioDurationSeconds <= SHORT_AUDIO_THRESHOLD_SEC) {
                    // Short dictation -> Groq Whisper Turbo first for instant ~180ms speed!
                    order.add(ProviderId.GROQ)
                    order.add(ProviderId.GEMINI)
                } else {
                    // Long horizon dictation -> Gemini 3.5 Transcribe Smart Mode first!
                    order.add(ProviderId.GEMINI)
                    order.add(ProviderId.GROQ)
                }
            } else {
                if (groqReady) order.add(ProviderId.GROQ)
                if (geminiReady) order.add(ProviderId.GEMINI)
            }

            if (openRouterReady) {
                order.add(ProviderId.OPENROUTER)
            }
            return order
        }

        fun computeFormattingProviderOrder(
            isGroqEnabled: Boolean,
            groqApiKey: String,
            isCerebrasEnabled: Boolean,
            cerebrasApiKey: String,
            isGeminiEnabled: Boolean,
            geminiApiKey: String,
            isOpenRouterEnabled: Boolean,
            openRouterApiKey: String
        ): List<ProviderId> {
            val list = mutableListOf<ProviderId>()
            if (isGroqEnabled && groqApiKey.trim().isNotEmpty()) list.add(ProviderId.GROQ)
            if (isCerebrasEnabled && cerebrasApiKey.trim().isNotEmpty()) list.add(ProviderId.CEREBRAS)
            if (isGeminiEnabled && geminiApiKey.trim().isNotEmpty()) list.add(ProviderId.GEMINI)
            if (isOpenRouterEnabled && openRouterApiKey.trim().isNotEmpty()) list.add(ProviderId.OPENROUTER)
            return list
        }
    }

    /**
     * Returns the ordered list of ASR (Audio-to-Text) providers based on:
     * 1. Which providers have BOTH a valid API key AND their toggle switch turned ON.
     * 2. In SMART_AUTO mode:
     *    - Short clips (<= 20s): Groq Whisper Large V3 Turbo first (~180ms!), Gemini second.
     *    - Long clips (> 20s): Gemini 3.5 Transcribe Smart Mode first (deep context), Groq second.
     */
    fun getAsrProviderOrder(audioDurationSeconds: Int): List<ProviderId> {
        return computeAsrProviderOrder(
            routingMode = prefs.providerRoutingMode,
            audioDurationSeconds = audioDurationSeconds,
            isGeminiEnabled = prefs.isGeminiEnabled,
            geminiApiKey = prefs.geminiApiKey,
            isGroqEnabled = prefs.isGroqEnabled,
            groqApiKey = prefs.groqApiKey,
            isOpenRouterEnabled = prefs.isOpenRouterEnabled,
            openRouterApiKey = prefs.openRouterApiKey
        )
    }

    /**
     * Executes Cloud Audio Transcription across the user's enabled & toggled providers.
     * Works 100% when Gemini key is removed and only Groq or OpenRouter is enabled!
     */
    suspend fun transcribeAudioSmart(
        audioFile: File,
        audioDurationSeconds: Int,
        customVocab: List<String>
    ): AsrExecutionResult = withContext(Dispatchers.IO) {
        val candidates = getAsrProviderOrder(audioDurationSeconds)

        for (provider in candidates) {
            try {
                when (provider) {
                    ProviderId.GROQ -> {
                        val key = prefs.groqApiKey.trim()
                        val res = groqWhisperClient.transcribeAudio(
                            audioFile = audioFile,
                            apiKey = key,
                            customVocabulary = customVocab
                        )
                        val text = res.text.trim()
                        if (text.isNotEmpty()) {
                            Log.i(TAG, "ASR succeeded via GROQ (${audioDurationSeconds}s clip, ${res.latencyMs}ms)")
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.GROQ_WHISPER,
                                alreadySmartFormatted = false
                            )
                        }
                    }

                    ProviderId.GEMINI -> {
                        val key = prefs.geminiApiKey.trim()
                        val res = geminiAdapter.transcribeAudioFile(
                            audioFile = audioFile,
                            rawApiKey = key,
                            customVocabulary = customVocab,
                            useVerbatimMode = false // Native Smart Mode
                        )
                        if (res.text.isNotBlank()) {
                            Log.i(TAG, "ASR succeeded via GEMINI (${res.modelId}, ${res.latencyMs}ms)")
                            return@withContext AsrExecutionResult(
                                transcript = res.text.trim(),
                                source = RecognitionSource.GEMINI_TRANSCRIBE,
                                alreadySmartFormatted = true
                            )
                        }
                    }

                    ProviderId.OPENROUTER -> {
                        val key = prefs.openRouterApiKey.trim()
                        val text = transcribeViaOpenRouterAudio(audioFile, key, customVocab).trim()
                        if (text.isNotEmpty()) {
                            Log.i(TAG, "ASR succeeded via OPENROUTER multimodal audio")
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.GEMINI_TRANSCRIBE,
                                alreadySmartFormatted = true
                            )
                        }
                    }

                    ProviderId.CEREBRAS -> {
                        // Cerebras is a text-only LLM provider (used in formatting step)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ASR provider $provider failed (${t.message}), trying next enabled provider...")
            }
        }

        AsrExecutionResult("", RecognitionSource.ANDROID_STT, false)
    }

    /**
     * Allows OpenRouter keys to transcribe audio directly using OpenRouter's multimodal audio input
     * (`google/gemini-2.5-flash` or `openai/gpt-4o-audio-preview`) when Gemini & Groq are disabled!
     */
    private fun transcribeViaOpenRouterAudio(
        audioFile: File,
        openRouterKey: String,
        customVocab: List<String>
    ): String {
        val b64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val format = if (audioFile.extension.lowercase() == "wav") "wav" else "m4a"
        val vocabHint = if (customVocab.isNotEmpty()) {
            " Custom vocabulary terms: ${customVocab.take(50).joinToString(", ")}."
        } else ""

        val payload = JSONObject().apply {
            put("model", "google/gemini-2.5-flash")
            put("temperature", 0.0)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Transcribe this speech accurately in its spoken language (Bengali, English, or mixed), removing verbal fillers and adding proper punctuation ('।' for Bangla sentences, '.' for English). Output ONLY the final text.$vocabHint")
                        })
                        .put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", b64)
                                put("format", format)
                            })
                        })
                    )
                }
            ))
        }

        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $openRouterKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("OpenRouter HTTP ${resp.code}: $body")
            return JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
        }
    }

    /**
     * Returns the ordered list of enabled Text Formatting providers:
     * Prioritizes ultra-fast Groq Llama 3.3 70B & Cerebras Llama 3.3 70B (~150ms),
     * then Gemini Flash-Lite, then OpenRouter!
     */
    fun getTextFormattingProviderOrder(): List<ProviderId> {
        return computeFormattingProviderOrder(
            isGroqEnabled = prefs.isGroqEnabled,
            groqApiKey = prefs.groqApiKey,
            isCerebrasEnabled = prefs.isCerebrasEnabled,
            cerebrasApiKey = prefs.cerebrasApiKey,
            isGeminiEnabled = prefs.isGeminiEnabled,
            geminiApiKey = prefs.geminiApiKey,
            isOpenRouterEnabled = prefs.isOpenRouterEnabled,
            openRouterApiKey = prefs.openRouterApiKey
        )
    }
}
