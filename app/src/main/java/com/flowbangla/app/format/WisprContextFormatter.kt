package com.flowbangla.app.format

import com.flowbangla.app.polish.LocalFillerCleaner

object WisprContextFormatter {

    private val NUMERIC_REGEX = Regex("""\b\d+\b|[০-৯]+""")
    private val URL_REGEX = Regex("""https?://\S+|www\.\S+""")
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /**
     * Applies Wispr Flow on-device cursor boundary formatting (< 1ms):
     * 1. Strips stiff trailing periods for 1-2 sentence messages in casual messaging apps.
     * 2. Prepends a leading space if the cursor is immediately preceded by a non-whitespace character.
     * 3. Lowers the first character if the user is dictating mid-sentence (e.g. after a comma, space, or emoji),
     *    except for English acronyms (e.g. "API", "AI") or the pronoun "I".
     */
    fun applyCursorBoundaryFormatting(
        text: String,
        context: ActiveFieldContext,
        dropCasualPeriod: Boolean = false
    ): String {
        if (text.isEmpty()) return ""

        var result = applyCasualMessagingPeriodPolicy(text, context.appCategory, dropCasualPeriod)
        result = result.trimStart()
        if (result.isEmpty()) return ""

        // 1. Mid-sentence lowercase alignment for Latin characters
        if (context.isMidSentenceContinuation && result.isNotEmpty()) {
            val firstChar = result[0]
            if (firstChar in 'A'..'Z') {
                val isAcronym = result.length > 1 && result[1].isUpperCase()
                val isPronounI = result.startsWith("I ") || result.startsWith("I'") || result == "I"
                if (!isAcronym && !isPronounI) {
                    result = firstChar.lowercaseChar() + result.substring(1)
                }
            }
        }

        // 2. Leading space alignment: ONLY if cursor is reliably confirmed to be preceded by non-whitespace
        if (context.needsLeadingSpace && result.isNotEmpty() && !result[0].isWhitespace()) {
            result = " $result"
        }

        return result
    }

    /**
     * In casual messaging apps (WhatsApp, Messenger, SMS), 1-2 sentence short messages
     * automatically omit the stiff trailing period while preserving '?' and '!'.
     */
    fun applyCasualMessagingPeriodPolicy(text: String, appCategory: AppCategory, dropCasualPeriod: Boolean): String {
        if (!dropCasualPeriod || appCategory != AppCategory.PERSONAL_MESSAGING) return text
        val trimmed = text.trimEnd()
        if (trimmed.endsWith(".") || trimmed.endsWith("।")) {
            val sentenceCount = trimmed.count { it == '.' || it == '।' || it == '?' || it == '!' }
            val newlineCount = trimmed.count { it == '\n' }
            if (sentenceCount in 1..2 && newlineCount == 0 && !trimmed.endsWith("...")) {
                return trimmed.dropLast(1)
            }
        }
        return text
    }

    /**
     * Validates that critical tokens (numbers, URLs, emails) present in the raw transcript
     * were faithfully preserved by the formatter. If any critical token was lost, returns false,
     * unless the raw transcript contained an explicit Backtrack self-correction cue.
     */
    fun areCriticalTokensPreserved(rawTranscript: String, formattedText: String): Boolean {
        val hasBacktrackCue = PromptBuilder.BACKTRACK_CUE_REGEX.containsMatchIn(rawTranscript)

        // Check numeric tokens
        val rawNumbers = NUMERIC_REGEX.findAll(rawTranscript).map { it.value }.toSet()
        val formattedNumbers = NUMERIC_REGEX.findAll(formattedText).map { it.value }.toSet()
        // If raw had numbers but formatted dropped them all, reject unless it was a backtrack
        if (rawNumbers.isNotEmpty() && formattedNumbers.isEmpty() && !hasBacktrackCue) return false

        // Check URLs
        val rawUrls = URL_REGEX.findAll(rawTranscript).map { it.value.lowercase() }.toSet()
        val formattedUrls = URL_REGEX.findAll(formattedText).map { it.value.lowercase() }.toSet()
        if (rawUrls.any { it !in formattedUrls } && !hasBacktrackCue) return false

        // Check Emails
        val rawEmails = EMAIL_REGEX.findAll(rawTranscript).map { it.value.lowercase() }.toSet()
        val formattedEmails = EMAIL_REGEX.findAll(formattedText).map { it.value.lowercase() }.toSet()
        if (rawEmails.any { it !in formattedEmails } && !hasBacktrackCue) return false

        return true
    }

    /**
     * Maps the active app category to a safe default document type.
     * Note: EMAIL app category maps to GENERAL_DICTATION to ensure Flow never inserts
     * synthetic headers (To/Subject/Dear) into email body fields.
     */
    fun mapAppCategoryToDocumentType(category: AppCategory): DocumentType {
        return when (category) {
            AppCategory.EMAIL -> DocumentType.GENERAL_DICTATION
            AppCategory.PERSONAL_MESSAGING -> DocumentType.CASUAL_MESSAGE
            AppCategory.WORK_MESSAGING -> DocumentType.CASUAL_MESSAGE
            AppCategory.NOTES_AND_DOCS -> DocumentType.PERSONAL_NOTE
            AppCategory.OTHER -> DocumentType.GENERAL_DICTATION
        }
    }
}
