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

        val BACKTRACK_OR_LIST_CUE_REGEX = Regex(
            pattern = """\b(no|not|wait|actually|scratch that|never mind|sorry|mean|instead|recipe|list|number|one|two|three|first|second|third|bullet)\b|(?:না|থাক|বাদ দাও|ভুল|আসলে|ওটা না|তালিকা|লিস্ট|এক|দুই|তিন)""",
            option = RegexOption.IGNORE_CASE
        )

        fun shouldUseInstantLocalFastPath(rawTranscript: String, cleanupLevel: AutoCleanupLevel): Boolean {
            if (cleanupLevel == AutoCleanupLevel.NONE) return true
            val words = rawTranscript.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            // Only use instant local fast-path for ultra-short (<= 3 words) utterances
            // that contain ZERO backtrack, negation, or list-building triggers!
            if (words.size > 3) return false
            return !BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(rawTranscript)
        }
    }

    suspend fun format(
        rawTranscript: String,
        recognitionSource: RecognitionSource,
        context: ActiveFieldContext = ActiveFieldContext(),
        isSmartMode: Boolean = (recognitionSource == RecognitionSource.GEMINI_TRANSCRIBE || recognitionSource == RecognitionSource.GEMINI_LIVE_STREAM)
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

        // 1. Expand voice snippets (standalone or embedded triggers)
        val workingTranscript = try {
            com.flowbangla.app.FlowBanglaApp.instance.snippetManager.expandSnippets(rawTranscript)
        } catch (_: Exception) {
            rawTranscript
        }

        // Build FormatPlan from user preferences and active app context
        val inferredDocType = WisprContextFormatter.mapAppCategoryToDocumentType(context.appCategory)
        val plan = FormatPlan(
            documentType = inferredDocType,
            numeralPreference = NumeralPreference.WESTERN,
            outputScript = OutputScript.AS_SPOKEN,
            protectedTokens = prefs.getPersonalVocabularyList().toSet(),
            isAutoDetected = true,
            cleanupLevel = prefs.autoCleanupLevel,
            appCategory = context.appCategory.name
        )

        // Fast-path: restricted to ultra-short (<= 3 words) simple phrases with ZERO backtrack/list triggers
        val canUseFastPath = shouldUseInstantLocalFastPath(workingTranscript, prefs.autoCleanupLevel) &&
            !workingTranscript.contains('\n') &&
            prefs.engineMode != EngineMode.OFFLINE_LOCAL_LLM

        if (canUseFastPath) {
            val fastEnvelope = LocalRulesFormatter.format(workingTranscript, plan)
            val dictionaryCleaned = applyDictionaryReplacementsSafely(fastEnvelope.formattedText)
            val fastFormatted = WisprContextFormatter.applyCursorBoundaryFormatting(
                text = dictionaryCleaned,
                context = context,
                dropCasualPeriod = prefs.casualMessagingDropPeriod
            )
            val latency = SystemClock.elapsedRealtime() - startTime
            return FormattingResult(
                rawTranscript = rawTranscript,
                formattedText = fastFormatted,
                documentType = fastEnvelope.documentType,
                recognitionSource = recognitionSource,
                formattingSource = FormattingSource.LOCAL_RULES,
                latencyMs = latency,
                fallbackUsed = false
            )
        }

        // Flow v4.1.0 Single-Pass Smart Mode Optimization:
        // gemini-3.5-transcribe and gemini-3.5-transcribe-live running in mode: { type: "smart" }
        // natively punctuate, strip fillers, correct speech, and structure paragraphs in 1 pass!
        // Sending long dictations (> 35 words) to a second LLM wastes 25-45s token-by-token.
        // Therefore, only invoke the second LLM pass if:
        // 1. Transcript is short-to-medium (4..35 words)
        // 2. Contains an explicit complex backtrack or list cue (BACKTRACK_OR_LIST_CUE_REGEX)
        // 3. User has smart polish enabled
        val wordCount = workingTranscript.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val isSmartCandidate = isSmartMode &&
            (recognitionSource == RecognitionSource.GEMINI_TRANSCRIBE || recognitionSource == RecognitionSource.GEMINI_LIVE_STREAM) &&
            prefs.engineMode == EngineMode.CLOUD_AUDIO

        val hasComplexBacktrack = BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(workingTranscript)
        val requiresSecondLlmPass = isSmartCandidate && (wordCount in 4..35) && hasComplexBacktrack && prefs.smartPolishEnabled

        if (isSmartCandidate && !requiresSecondLlmPass) {
            val resolvedBacktracks = LocalBacktrackResolver.resolveOfflineBacktracks(workingTranscript)
            val postProcessed = TranscriptPostProcessor.process(resolvedBacktracks, numeralPreference = plan.numeralPreference)
            val dictionaryCleaned = applyDictionaryReplacementsSafely(postProcessed)
            val smartFormatted = WisprContextFormatter.applyCursorBoundaryFormatting(
                text = dictionaryCleaned,
                context = context,
                dropCasualPeriod = prefs.casualMessagingDropPeriod
            )
            val latency = SystemClock.elapsedRealtime() - startTime
            return FormattingResult(
                rawTranscript = rawTranscript,
                formattedText = smartFormatted,
                documentType = plan.documentType,
                recognitionSource = recognitionSource,
                formattingSource = FormattingSource.GEMINI_SMART_SINGLE_PASS,
                latencyMs = latency,
                fallbackUsed = false
            )
        }

        // Try primary formatting engine based on configured engine mode
        var envelope: FormattedEnvelope? = null
        var formattingSource = FormattingSource.LOCAL_RULES

        try {
            when (prefs.engineMode) {
                EngineMode.CLOUD_AUDIO -> {
                    // In Cloud Audio, Gemini or Groq formats the text
                    envelope = geminiFormatter.formatText(workingTranscript, plan)
                    if (envelope != null) {
                        formattingSource = FormattingSource.GEMINI_CLOUD
                    } else if (prefs.groqApiKey.isNotBlank()) {
                        envelope = groqFormatter.formatText(workingTranscript, plan)
                        if (envelope != null) formattingSource = FormattingSource.GROQ_CLOUD
                    }
                }
                EngineMode.HYBRID_CLOUD_POLISH -> {
                    when (prefs.cloudPolishProvider) {
                        CloudPolishProvider.GEMINI -> {
                            envelope = geminiFormatter.formatText(workingTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.GEMINI_CLOUD
                        }
                        CloudPolishProvider.GROQ -> {
                            envelope = groqFormatter.formatText(workingTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.GROQ_CLOUD
                        }
                        CloudPolishProvider.CEREBRAS -> {
                            envelope = cerebrasFormatter.formatText(workingTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.CEREBRAS_CLOUD
                        }
                        CloudPolishProvider.OPENROUTER -> {
                            envelope = openRouterFormatter.formatText(workingTranscript, plan)
                            if (envelope != null) formattingSource = FormattingSource.OPENROUTER_CLOUD
                        }
                    }
                }
                EngineMode.OFFLINE_LOCAL_LLM -> {
                    val localResult = localLlmEngine.polishOfflineWithLlm(workingTranscript)
                    if (localResult.isNotBlank() && localResult != workingTranscript) {
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
                rawTranscript = workingTranscript,
                formattedText = postProcessed,
                protectedTokens = plan.protectedTokens
            )

            val tokensPreserved = WisprContextFormatter.areCriticalTokensPreserved(workingTranscript, postProcessed)

            if (validation is ValidationResult.Valid && tokensPreserved) {
                val dictionaryCleaned = applyDictionaryReplacementsSafely(postProcessed)
                val boundaryFormatted = WisprContextFormatter.applyCursorBoundaryFormatting(
                    text = dictionaryCleaned,
                    context = context,
                    dropCasualPeriod = prefs.casualMessagingDropPeriod
                )
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

        // Guaranteed fallback: Local rules formatting on working transcript (ZERO DATA LOSS)
        val fallbackEnvelope = LocalRulesFormatter.format(workingTranscript, plan)
        val dictionaryCleaned = applyDictionaryReplacementsSafely(fallbackEnvelope.formattedText)
        val safeCleaned = WisprContextFormatter.applyCursorBoundaryFormatting(
            text = dictionaryCleaned,
            context = context,
            dropCasualPeriod = prefs.casualMessagingDropPeriod
        )
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

    /**
     * Fast formatting pass for Smart Transcripts. If already smart-transcribed
     * and no complex backtrack cues exist, processes via local rules in <2ms.
     */
    suspend fun formatSmartTranscript(
        rawTranscript: String,
        fieldContext: ActiveFieldContext? = null,
        alreadySmartTranscribed: Boolean = true
    ): String {
        val ctx = fieldContext ?: ActiveFieldContext()
        val source = if (alreadySmartTranscribed) RecognitionSource.GEMINI_TRANSCRIBE else RecognitionSource.GROQ_WHISPER
        val result = format(rawTranscript, source, ctx, isSmartMode = alreadySmartTranscribed)
        return result.formattedText
    }

    /**
     * Instant local rules formatting without LLM calls (< 2ms).
     */
    fun formatLocalInstantOnly(
        rawTranscript: String,
        fieldContext: ActiveFieldContext? = null
    ): String {
        val ctx = fieldContext ?: ActiveFieldContext()
        val plan = FormatPlan(
            documentType = WisprContextFormatter.mapAppCategoryToDocumentType(ctx.appCategory),
            numeralPreference = NumeralPreference.WESTERN,
            outputScript = OutputScript.AS_SPOKEN,
            protectedTokens = prefs.getPersonalVocabularyList().toSet(),
            isAutoDetected = true,
            cleanupLevel = prefs.autoCleanupLevel,
            appCategory = ctx.appCategory.name
        )
        val fastEnvelope = LocalRulesFormatter.format(rawTranscript, plan)
        val dictionaryCleaned = applyDictionaryReplacementsSafely(fastEnvelope.formattedText)
        return WisprContextFormatter.applyCursorBoundaryFormatting(
            text = dictionaryCleaned,
            context = ctx,
            dropCasualPeriod = prefs.casualMessagingDropPeriod
        )
    }

    private fun applyDictionaryReplacementsSafely(text: String): String {
        return try {
            com.flowbangla.app.FlowBanglaApp.instance.personalDictionaryManager.applyReplacements(text)
        } catch (_: Exception) {
            text
        }
    }

    fun release() {
        localLlmEngine.release()
    }
}
