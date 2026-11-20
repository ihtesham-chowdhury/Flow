package com.flowbangla.app.api

import android.util.Log
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.data.ProviderId
import com.flowbangla.app.data.ProviderRoutingMode
import com.flowbangla.app.format.RecognitionSource
import com.flowbangla.app.model.SttLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
            val deepgramReady = isDeepgramEnabled && deepgramApiKey.trim().isNotEmpty()
            val sarvamReady = isSarvamEnabled && sarvamApiKey.trim().isNotEmpty()
            val sonioxReady = isSonioxEnabled && sonioxApiKey.trim().isNotEmpty()

            val order = mutableListOf<ProviderId>()

            if (!isBanglaPreferred) {
                // ENGLISH MODE: Groq Whisper Large V3 Turbo is #1 for ~180ms blazing speed!
                if (groqReady) order.add(ProviderId.GROQ)
                if (deepgramReady) order.add(ProviderId.DEEPGRAM)
                if (sonioxReady) order.add(ProviderId.SONIOX)
                if (geminiReady) order.add(ProviderId.GEMINI)
                if (sarvamReady) order.add(ProviderId.SARVAM)
            } else {
                // BANGLA / MIXED MODE:
                // STRICT INVARIANT: NEVER include ProviderId.GROQ (Whisper) in BANGLA or MIXED!
                if (sarvamReady) order.add(ProviderId.SARVAM)
                if (sonioxReady) order.add(ProviderId.SONIOX)
                if (geminiReady) order.add(ProviderId.GEMINI)
                if (deepgramReady) order.add(ProviderId.DEEPGRAM)
            }

            return order
        }

        fun computeFormattingProviderOrder(
            isGroqEnabled: Boolean,
            groqApiKey: String,
            isGeminiEnabled: Boolean,
            geminiApiKey: String
        ): List<ProviderId> {
            val list = mutableListOf<ProviderId>()
            if (isGroqEnabled && groqApiKey.trim().isNotEmpty()) list.add(ProviderId.GROQ)
            if (isGeminiEnabled && geminiApiKey.trim().isNotEmpty()) list.add(ProviderId.GEMINI)
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
                ProviderId.SARVAM
            )
            SpeechLang.BANGLA, SpeechLang.MIXED -> listOf(
                // STRICT INVARIANT: NEVER include ProviderId.GROQ (Whisper) in BANGLA or MIXED!
                ProviderId.SARVAM,
                ProviderId.SONIOX,
                ProviderId.GEMINI,
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
        val candidates = getOrderedAsrTiers(lang)

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

                    ProviderId.DEEPGRAM -> {
                        val key = prefs.deepgramApiKey.trim()
                        val text = deepgramClient.transcribeAudio(audioFile, key, lang).trim()
                        if (text.isNotBlank()) {
                            healthTracker.recordSuccess(providerKey, audioDurationSeconds)
                            return@withContext AsrExecutionResult(
                                transcript = text,
                                source = RecognitionSource.DEEPGRAM_NOVA,
                                alreadySmartFormatted = true
                            )
                        }
                    }
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

    fun getTextFormattingProviderOrder(): List<ProviderId> {
        return computeFormattingProviderOrder(
            isGroqEnabled = prefs.isGroqEnabled,
            groqApiKey = prefs.groqApiKey,
            isGeminiEnabled = prefs.isGeminiEnabled,
            geminiApiKey = prefs.geminiApiKey
        )
    }
}
