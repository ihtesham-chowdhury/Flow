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
    private val groqWhisperClient: GroqWhisperClient,
    private val elevenLabsClient: ElevenLabsScribeClient = ElevenLabsScribeClient(http),
    private val deepgramClient: DeepgramNovaClient = DeepgramNovaClient(http)
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
            openRouterApiKey: String,
            isElevenLabsEnabled: Boolean = false,
            elevenLabsApiKey: String = "",
            isDeepgramEnabled: Boolean = false,
            deepgramApiKey: String = "",
            isBanglaPreferred: Boolean = true
        ): List<ProviderId> {
            val geminiReady = isGeminiEnabled && geminiApiKey.trim().isNotEmpty()
            val groqReady = isGroqEnabled && groqApiKey.trim().isNotEmpty()
            val elevenLabsReady = isElevenLabsEnabled && elevenLabsApiKey.trim().isNotEmpty()
            val deepgramReady = isDeepgramEnabled && deepgramApiKey.trim().isNotEmpty()
            val openRouterReady = isOpenRouterEnabled && openRouterApiKey.trim().isNotEmpty()

            val order = mutableListOf<ProviderId>()

            if (routingMode == ProviderRoutingMode.SMART_AUTO && geminiReady && groqReady) {
                if (isBanglaPreferred) {
                    // BANGLA / MIXED -> Gemini first (99% native accuracy), then ElevenLabs, then Groq
                    order.add(ProviderId.GEMINI)
                    if (elevenLabsReady) order.add(ProviderId.ELEVENLABS)
                    if (deepgramReady) order.add(ProviderId.DEEPGRAM)
                    order.add(ProviderId.GROQ)
                } else {
                    // ENGLISH -> Groq first (~180ms blazing speed), then Deepgram, then Gemini
                    order.add(ProviderId.GROQ)
                    if (deepgramReady) order.add(ProviderId.DEEPGRAM)
                    order.add(ProviderId.GEMINI)
                    if (elevenLabsReady) order.add(ProviderId.ELEVENLABS)
                }
            } else {
                // MANUAL_PRIORITY or fallback: Groq first then Gemini, followed by other enabled providers
                if (groqReady) order.add(ProviderId.GROQ)
                if (geminiReady) order.add(ProviderId.GEMINI)
                if (elevenLabsReady) order.add(ProviderId.ELEVENLABS)
                if (deepgramReady) order.add(ProviderId.DEEPGRAM)
            }

            if (openRouterReady && ProviderId.OPENROUTER !in order) {
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
     * 1. Enabled provider switches with valid API keys.
     * 2. In SMART_AUTO mode:
     *    - Language awareness: English prefers Groq (~180ms), Bangla prefers Gemini (99% native).
     */
    fun getAsrProviderOrder(
        audioDurationSeconds: Int = 1,
        surroundingTextBeforeCursor: String = ""
    ): List<ProviderId> {
        val cursorHasBangla = Regex("""[ঀ-৿]""").containsMatchIn(surroundingTextBeforeCursor)
        val isBanglaPreferred = prefs.sttLanguage.localeTag.startsWith("bn") || cursorHasBangla
        return computeAsrProviderOrder(
            routingMode = prefs.providerRoutingMode,
            audioDurationSeconds = audioDurationSeconds,
            isGeminiEnabled = prefs.isGeminiEnabled,
            geminiApiKey = prefs.geminiApiKey,
            isGroqEnabled = prefs.isGroqEnabled,
            groqApiKey = prefs.groqApiKey,
            isOpenRouterEnabled = prefs.isOpenRouterEnabled,
            openRouterApiKey = prefs.openRouterApiKey,
            isElevenLabsEnabled = prefs.isElevenLabsEnabled,
            elevenLabsApiKey = prefs.elevenLabsApiKey,
            isDeepgramEnabled = prefs.isDeepgramEnabled,
            deepgramApiKey = prefs.deepgramApiKey,
            isBanglaPreferred = isBanglaPreferred
        )
    }

    /**
     * Executes Cloud Audio Transcription across the user's enabled & toggled providers.
     * Features:
     * - Language-aware routing (English -> Groq; Bangla -> Gemini).
     * - Groq Reactive Safety Net: if Groq produces >= 10% Bengali characters or gibberish,
     *   automatically discards the mangled transcript and re-transcribes with Gemini!
     * - Multi-model OpenRouter audio cascade supporting standard 16kHz WAV.
     * - ElevenLabs Scribe and Deepgram Nova-3 enterprise integrations.
     */
    suspend fun transcribeAudioSmart(
        audioFile: File,
        audioDurationSeconds: Int,
        customVocab: List<String>,
        surroundingTextBeforeCursor: String = ""
    ): AsrExecutionResult = withContext(Dispatchers.IO) {
        val geminiReady = prefs.isGeminiEnabled && prefs.geminiApiKey.trim().isNotEmpty()
        val elevenLabsReady = prefs.isElevenLabsEnabled && prefs.elevenLabsApiKey.trim().isNotEmpty()

        val candidates = getAsrProviderOrder(audioDurationSeconds, surroundingTextBeforeCursor)
        var lastError = ""

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
                            // CRITICAL LANGUAGE-AWARE SAFETY NET:
                            // If Groq was tried first (e.g. English mode), but the user actually spoke Bangla
                            // (so Groq output >= 10% Bengali script like "যতি কাজ না করে... কোতা বলা জ")
                            // OR triggered GibberishGuard, AND Gemini (or ElevenLabs) is enabled:
                            // Automatically discard Groq's mangled Bangla and re-transcribe with Gemini!
                            val bengaliCharCount = text.count { it in 'ঀ'..'৿' }
                            val letterCount = text.count { it.isLetter() }.coerceAtLeast(1)
                            val isActuallyBanglaSpeech = (bengaliCharCount.toFloat() / letterCount.toFloat()) >= 0.10f
                            val isCorrupted = com.flowbangla.app.format.GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(text)

                            if ((isActuallyBanglaSpeech || isCorrupted) && (geminiReady || elevenLabsReady)) {
                                Log.i(TAG, "Groq detected Bangla speech or corruption; auto-switching to Gemini/ElevenLabs for native Bangla accuracy!")
                                continue // Advances loop immediately to GEMINI / ELEVENLABS!
                            }

                            val sanitized = com.flowbangla.app.format.GibberishAndHallucinationGuard.sanitizeCorruptedTokens(text)
                            if (sanitized.isNotEmpty()) {
                                Log.i(TAG, "ASR succeeded via GROQ (${audioDurationSeconds}s clip, ${res.latencyMs}ms)")
                                return@withContext AsrExecutionResult(
                                    transcript = sanitized,
                                    source = RecognitionSource.GROQ_WHISPER,
                                    alreadySmartFormatted = false
                                )
                            }
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

                    ProviderId.ELEVENLABS -> {
                        val key = prefs.elevenLabsApiKey.trim()
                        val text = elevenLabsClient.transcribeAudio(audioFile, key).trim()
                        if (text.isNotBlank()) {
                            Log.i(TAG, "ASR succeeded via ElevenLabs Scribe")
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.ELEVENLABS_SCRIBE,
                                alreadySmartFormatted = false
                            )
                        }
                    }

                    ProviderId.DEEPGRAM -> {
                        val key = prefs.deepgramApiKey.trim()
                        val text = deepgramClient.transcribeAudio(audioFile, key).trim()
                        if (text.isNotBlank()) {
                            Log.i(TAG, "ASR succeeded via Deepgram Nova-3")
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.DEEPGRAM_NOVA,
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
                                source = RecognitionSource.OPENROUTER_AUDIO,
                                alreadySmartFormatted = true
                            )
                        }
                    }

                    ProviderId.CEREBRAS -> {
                        // Cerebras is a text-only LLM provider (used in formatting step)
                    }
                }
            } catch (t: Throwable) {
                lastError = "${provider.name}: ${t.message}"
                Log.w(TAG, "Provider $provider failed: ${t.message}")
            }
        }

        throw RuntimeException(lastError.ifEmpty { "No enabled cloud ASR provider succeeded" })
    }

    /**
     * Executes OpenRouter audio transcription using a multi-model cascade across verified
     * audio-input models (supporting both free and paid OpenRouter keys).
     */
    private fun transcribeViaOpenRouterAudio(
        audioFile: File,
        openRouterKey: String,
        customVocab: List<String>
    ): String {
        val cleanKey = openRouterKey.trim()
        val audioBytes = audioFile.readBytes()
        val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val headerMeta = com.flowbangla.app.audio.AudioFileInspector.inspectAndValidate(audioFile)
        val format = when (headerMeta.verifiedMimeType) {
            "audio/wav" -> "wav"
            "audio/mp3", "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            else -> "m4a"
        }

        val vocabHint = if (customVocab.isNotEmpty()) {
            " Custom vocabulary terms: ${customVocab.take(50).joinToString(", ")}."
        } else ""

        val promptText = "Transcribe this audio recording accurately in its exact spoken language(s) (Bengali, English, or mixed). " +
            "Remove verbal hesitations (um, uh, উম, অ্যাঁ), resolve self-corrections, and add natural punctuation " +
            "('।' for Bengali sentences, '.' for English). Output ONLY the clean transcript text with no preamble.$vocabHint"

        // 1. Try OpenRouter Chat Completions Multimodal Audio across verified audio-capable models
        val chatAudioModels = listOf(
            "google/gemini-2.5-flash-lite",
            "google/gemini-2.5-flash",
            "google/gemini-2.0-flash-001",
            "google/gemini-2.0-flash-lite-001",
            "google/gemini-2.0-flash-exp:free",
            "openai/gpt-4o-audio-preview"
        )

        var lastErrorMsg = ""
        for (modelSlug in chatAudioModels) {
            try {
                val chatPayload = JSONObject().apply {
                    put("model", modelSlug)
                    put("temperature", 0.0)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray()
                                .put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", promptText)
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
                    .header("Authorization", "Bearer $cleanKey")
                    .header("HTTP-Referer", "https://github.com/ihtesham-chowdhury/Flow")
                    .header("X-Title", "Flow Voice Dictation")
                    .header("Content-Type", "application/json")
                    .post(chatPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = JSONObject(body)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            .orEmpty()
                            .trim()
                        if (text.isNotEmpty()) return text
                    } else {
                        lastErrorMsg = "HTTP ${resp.code} on $modelSlug: ${body.take(120)}"
                    }
                }
            } catch (t: Throwable) {
                lastErrorMsg = "${t.message}"
            }
        }

        // 2. Fallback: Try OpenRouter Dedicated STT Endpoint (/api/v1/audio/transcriptions)
        val sttModels = listOf("openai/whisper-large-v3", "openai/whisper-1")
        for (sttModel in sttModels) {
            try {
                val sttPayload = JSONObject().apply {
                    put("model", sttModel)
                    put("temperature", 0)
                    put("input_audio", JSONObject().apply {
                        put("data", b64)
                        put("format", format)
                    })
                }
                val req = Request.Builder()
                    .url("https://openrouter.ai/api/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $cleanKey")
                    .header("Content-Type", "application/json")
                    .post(sttPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = JSONObject(body).optString("text", "").trim()
                        if (text.isNotEmpty()) return text
                    } else {
                        lastErrorMsg = "HTTP ${resp.code} on $sttModel: ${body.take(120)}"
                    }
                }
            } catch (t: Throwable) {
                lastErrorMsg = "${t.message}"
            }
        }

        throw RuntimeException("OpenRouter Audio Failed ($lastErrorMsg)")
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
