package com.flowbangla.app.format

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.CloudPolishProvider
import com.flowbangla.app.model.EngineMode
import com.flowbangla.app.offline.LocalLlmPolishEngine

/**
 * Intelligent Formatting Chain for Flow v2.2.0.
 * Decouples speech recognition from formatting, infers document-type layout,
 * validates against hallucination/translation, and guarantees zero data loss with safe fallbacks.
 */
class FormattingChain(
    private val context: Context,
    private val prefs: PreferencesManager
) {

    private val geminiFormatter = GeminiTextFormatter(apiKeyProvider = { prefs.geminiApiKey })
    private val groqFormatter = OpenAiCompatibleTextFormatter(
        endpointUrl = CloudPolishProvider.GROQ.endpointUrl,
        apiKeyProvider = { prefs.groqApiKey },
        modelProvider = { prefs.groqModelName.ifBlank { CloudPolishProvider.GROQ.defaultModel } }
    )
    private val cerebrasFormatter = OpenAiCompatibleTextFormatter(
        endpointUrl = CloudPolishProvider.CEREBRAS.endpointUrl,
        apiKeyProvider = { prefs.cerebrasApiKey },
        modelProvider = { CloudPolishProvider.CEREBRAS.defaultModel }
    )
    private val openRouterFormatter = OpenAiCompatibleTextFormatter(
        endpointUrl = CloudPolishProvider.OPENROUTER.endpointUrl,
        apiKeyProvider = { prefs.openRouterApiKey },
        modelProvider = { CloudPolishProvider.OPENROUTER.defaultModel }
    )

    private val localLlmEngine = LocalLlmPolishEngine(context, prefs)

    companion object {
        private const val TAG = "FormattingChain"
    }

    suspend fun format(
        rawTranscript: String,
        recognitionSource: RecognitionSource,
        context: ActiveFieldContext = ActiveFieldContext()
    ): FormattingResult {
        if (rawTranscript.isBlank()) {
            return FormattingResult(
                rawTranscript = "",
                formattedText = "",
                documentType = DocumentType.GENERAL_DICTATION,
                recognitionSource = recognitionSource,
                formattingSource = FormattingSource.RAW_FALLBACK,
                latencyMs = 0
            )
        }

        val startTime = SystemClock.elapsedRealtime()

        // Build FormatPlan from user preferences and active app context
        val inferredDocType = WisprContextFormatter.mapAppCategoryToDocumentType(context.appCategory)
        val plan = FormatPlan(
            documentType = inferredDocType,
            numeralPreference = NumeralPreference.WESTERN,
            outputScript = OutputScript.AS_SPOKEN,
            protectedTokens = prefs.getPersonalVocabularyList().toSet(),
            isAutoDetected = true
        )

        // Try primary formatting engine based on configured engine mode
        var envelope: FormattedEnvelope? = null
        var formattingSource = FormattingSource.LOCAL_RULES

        try {
            when (prefs.engineMode) {
                EngineMode.CLOUD_AUDIO -> {
                    // In Cloud Audio, Gemini or Groq formats the text
                    envelope = geminiFormatter.formatText(rawTranscript, plan)
                    if (envelope != null) {
                        formattingSource = FormattingSource.GEMINI_CLOUD
                    } else if (prefs.groqApiKey.isNotBlank()) {
                        envelope = groqFormatter.formatText(rawTranscript, plan)
                        if (envelope != null) formattingSource = FormattingSource.GROQ_CLOUD
                    }
                }
                EngineMode.HYBRID_CLOUD_POLISH -> {
                    when (prefs.cloudPolishProvider) {
                        CloudPolishProvider.GEMINI -> {
                            envelope = geminiFormatter.formatText(rawTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.GEMINI_CLOUD
                        }
                        CloudPolishProvider.GROQ -> {
                            envelope = groqFormatter.formatText(rawTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.GROQ_CLOUD
                        }
                        CloudPolishProvider.CEREBRAS -> {
                            envelope = cerebrasFormatter.formatText(rawTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.CEREBRAS_CLOUD
                        }
                        CloudPolishProvider.OPENROUTER -> {
                            envelope = openRouterFormatter.formatText(rawTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.OPENROUTER_CLOUD
                        }
                    }
                }
                EngineMode.OFFLINE_LOCAL_LLM -> {
                    val localResult = localLlmEngine.polishOfflineWithLlm(rawTranscript)
                    if (localResult.isNotBlank() && localResult != rawTranscript) {
                        envelope = FormattedEnvelope(
                            formattedText = localResult,
                            documentType = plan.documentType,
                            isParsedFromJson = false
                        )
                        formattingSource = FormattingSource.LOCAL_LLM
                    }
                }
                EngineMode.OFFLINE_LIGHTWEIGHT -> {
                    // Offline lightweight uses local deterministic rules directly
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary formatter failed: ${e.message}")
        }

        // Apply post-processing and validation if cloud/LLM returned text
        if (envelope != null && envelope.formattedText.isNotBlank()) {
            val postProcessed = TranscriptPostProcessor.process(
                envelope.formattedText,
                numeralPreference = plan.numeralPreference
            )

            val validation = FormatValidator.validate(
                rawTranscript = rawTranscript,
                formattedText = postProcessed,
                protectedTokens = plan.protectedTokens
            )

            val tokensPreserved = WisprContextFormatter.areCriticalTokensPreserved(rawTranscript, postProcessed)

            if (validation is ValidationResult.Valid && tokensPreserved) {
                val boundaryFormatted = WisprContextFormatter.applyCursorBoundaryFormatting(postProcessed, context)
                val latency = SystemClock.elapsedRealtime() - startTime
                return FormattingResult(
                    rawTranscript = rawTranscript,
                    formattedText = boundaryFormatted,
                    documentType = envelope.documentType,
                    recognitionSource = recognitionSource,
                    formattingSource = formattingSource,
                    latencyMs = latency,
                    fallbackUsed = false
                )
            } else {
                val reason = if (!tokensPreserved) "Critical tokens/numbers dropped" else (validation as ValidationResult.Invalid).reason
                Log.w(TAG, "Validation rejected output ($reason). Falling back to safe local rules.")
            }
        }

        // Guaranteed fallback: Local rules formatting on raw transcript (ZERO DATA LOSS)
        val fallbackEnvelope = LocalRulesFormatter.format(rawTranscript, plan)
        val safeCleaned = WisprContextFormatter.applyCursorBoundaryFormatting(fallbackEnvelope.formattedText, context)
        val latency = SystemClock.elapsedRealtime() - startTime

        return FormattingResult(
            rawTranscript = rawTranscript,
            formattedText = safeCleaned,
            documentType = fallbackEnvelope.documentType,
            recognitionSource = recognitionSource,
            formattingSource = FormattingSource.LOCAL_RULES,
            latencyMs = latency,
            fallbackUsed = true
        )
    }

    fun release() {
        localLlmEngine.release()
    }
}
