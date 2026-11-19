package com.flowbangla.app.api

import android.util.Base64
import android.util.Log
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.data.ProviderId
import com.flowbangla.app.data.ProviderRoutingMode
import com.flowbangla.app.format.RecognitionSource
import com.flowbangla.app.model.SttLanguage
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

enum class SpeechLang {
    MIXED,   // Default: Bangla + English code-switching
    BANGLA,  // Pure Bangla
    ENGLISH  // Pure English (Unlocks Groq Whisper Turbo ~180ms!)
}

class SmartProviderRouter(
    private val http: OkHttpClient,
    private val prefs: PreferencesManager,
    private val geminiAdapter: GeminiInteractionsAdapter,
    private val groqWhisperClient: GroqWhisperClient,
    private val elevenLabsClient: ElevenLabsScribeClient = ElevenLabsScribeClient(http),
    private val deepgramClient: DeepgramNovaClient = DeepgramNovaClient(http),
    private val sarvamClient: SarvamSaarasClient = SarvamSaarasClient(http),
    private val sonioxClient: SonioxSttClient = SonioxSttClient(http),
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker()
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
            isSarvamEnabled: Boolean = false,
            sarvamApiKey: String = "",
            isSonioxEnabled: Boolean = false,
            sonioxApiKey: String = "",
            isBanglaPreferred: Boolean = true
        ): List<ProviderId> {
            val geminiReady = isGeminiEnabled && geminiApiKey.trim().isNotEmpty()
            val groqReady = isGroqEnabled && groqApiKey.trim().isNotEmpty()
            val elevenLabsReady = isElevenLabsEnabled && elevenLabsApiKey.trim().isNotEmpty()
            val deepgramReady = isDeepgramEnabled && deepgramApiKey.trim().isNotEmpty()
            val sarvamReady = isSarvamEnabled && sarvamApiKey.trim().isNotEmpty()
            val sonioxReady = isSonioxEnabled && sonioxApiKey.trim().isNotEmpty()
            val openRouterReady = isOpenRouterEnabled && openRouterApiKey.trim().isNotEmpty()

            val order = mutableListOf<ProviderId>()

            if (!isBanglaPreferred) {
                // ENGLISH MODE: Groq Whisper Large V3 Turbo is #1 for ~180ms blazing speed!
                if (groqReady) order.add(ProviderId.GROQ)
                if (deepgramReady) order.add(ProviderId.DEEPGRAM)
                if (sonioxReady) order.add(ProviderId.SONIOX)
                if (geminiReady) order.add(ProviderId.GEMINI)
                if (elevenLabsReady) order.add(ProviderId.ELEVENLABS)
                if (sarvamReady) order.add(ProviderId.SARVAM)
            } else {
                // BANGLA / MIXED MODE:
                // STRICT INVARIANT: NEVER include ProviderId.GROQ (Whisper) in BANGLA or MIXED!
                if (sarvamReady) order.add(ProviderId.SARVAM)
                if (sonioxReady) order.add(ProviderId.SONIOX)
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

    fun getSpeechLangFromContext(surroundingTextBeforeCursor: String = ""): SpeechLang {
        if (prefs.sttLanguage == SttLanguage.ENGLISH) {
            val cursorHasBangla = Regex("""[ঀ-৿]""").containsMatchIn(surroundingTextBeforeCursor)
            return if (cursorHasBangla) SpeechLang.MIXED else SpeechLang.ENGLISH
        }
        return SpeechLang.MIXED
    }

    fun getOrderedAsrTiers(lang: SpeechLang): List<ProviderId> {
        val rawOrder = when (lang) {
            SpeechLang.ENGLISH -> listOf(
                ProviderId.GROQ,
                ProviderId.DEEPGRAM,
                ProviderId.SONIOX,
                ProviderId.GEMINI,
                ProviderId.ELEVENLABS,
                ProviderId.SARVAM
            )
            SpeechLang.BANGLA, SpeechLang.MIXED -> listOf(
                // STRICT INVARIANT: NEVER include ProviderId.GROQ (Whisper) in BANGLA or MIXED!
                ProviderId.SARVAM,
                ProviderId.SONIOX,
                ProviderId.GEMINI,
                ProviderId.ELEVENLABS,
                ProviderId.DEEPGRAM
            )
        }

        return rawOrder.filter { provider ->
            prefs.isProviderEnabledAndConfigured(provider) &&
                healthTracker.isProviderAvailable(provider.name.lowercase())
        }
    }

    fun getAsrProviderOrder(
        audioDurationSeconds: Int = 1,
        surroundingTextBeforeCursor: String = ""
    ): List<ProviderId> {
        val lang = getSpeechLangFromContext(surroundingTextBeforeCursor)
        return getOrderedAsrTiers(lang)
    }

    suspend fun transcribeAudioSmart(
        audioFile: File,
        audioDurationSeconds: Int,
        customVocab: List<String>,
        surroundingTextBeforeCursor: String = ""
    ): AsrExecutionResult = withContext(Dispatchers.IO) {
        val lang = getSpeechLangFromContext(surroundingTextBeforeCursor)
        val candidates = getOrderedAsrTiers(lang).toMutableList()

        if (prefs.isOpenRouterEnabled && prefs.openRouterApiKey.isNotBlank() && ProviderId.OPENROUTER !in candidates) {
            candidates.add(ProviderId.OPENROUTER)
        }

        var lastError = ""

        for (provider in candidates) {
            val providerKey = provider.name.lowercase()
            try {
                when (provider) {
                    ProviderId.SARVAM -> {
                        val key = prefs.sarvamApiKey.trim()
                        val text = sarvamClient.transcribeAudio(audioFile, key)
                        if (text.isNotBlank()) {
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.SARVAM_SAARAS,
                                alreadySmartFormatted = false
                            )
                        }
                    }

                    ProviderId.SONIOX -> {
                        val key = prefs.sonioxApiKey.trim()
                        val text = sonioxClient.transcribeAudio(audioFile, key)
                        if (text.isNotBlank()) {
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.SONIOX_STT,
                                alreadySmartFormatted = false
                            )
                        }
                    }

                    ProviderId.GROQ -> {
                        val key = prefs.groqApiKey.trim()
                        val res = groqWhisperClient.transcribeAudio(
                            audioFile = audioFile,
                            apiKey = key,
                            customVocabulary = customVocab
                        )
                        val text = res.text.trim()
                        if (text.isNotEmpty()) {
                            val sanitized = com.flowbangla.app.format.GibberishAndHallucinationGuard.sanitizeCorruptedTokens(text)
                            if (sanitized.isNotEmpty()) {
                                healthTracker.recordSuccess(providerKey, audioDurationSeconds)
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
                            useVerbatimMode = false
                        )
                        if (res.text.isNotBlank()) {
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
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
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
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
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
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
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.OPENROUTER_AUDIO,
                                alreadySmartFormatted = true
                            )
                        }
                    }

                    ProviderId.CEREBRAS -> {}
                }
            } catch (t: Throwable) {
                val httpCode = if (t is FlowApiException) t.httpCode else 500
                healthTracker.recordHttpError(
                    providerKey = providerKey,
                    modelId = null,
                    httpCode = httpCode,
                    retryAfterSec = null,
                    reason = t.message ?: "Error"
                )
                lastError = "${provider.name}: ${t.message}"
                Log.w(TAG, "Provider $provider failed: ${t.message}")
            }
        }

        throw RuntimeException(lastError.ifEmpty { "No enabled cloud ASR provider succeeded" })
    }

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

        val chatAudioModels = listOf(
            "google/gemini-2.5-flash-lite",
            "google/gemini-2.5-flash",
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
