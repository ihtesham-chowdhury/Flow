package com.flowbangla.app.format

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

object FormatValidator {

    private val CHATTY_PREAMBLE_PATTERNS = listOf(
        Regex("(?i)^here is (the|your) (formatted|transcribed|polished) text:?"),
        Regex("(?i)^sure,? here (is|are)"),
        Regex("(?i)^i have (formatted|polished|cleaned|transcribed)"),
        Regex("(?i)^as an ai"),
        Regex("(?i)^here('s| is) what (i|you)"),
        Regex("(?i)^the formatted text is:?")
    )

    private val SYNTHETIC_HEADER_REGEX = Regex("(?i)(?:^|\\n)\\s*(?:To|From|Subject|Date|Cc|Bcc):\\s*")
    private val SYNTHETIC_CLOSING_REGEX = Regex("(?i)\\b(?:Sincerely,|Best regards,|Warm regards,|Yours faithfully,|Yours truly,|Kind regards,)\\b")
    private val SYNTHETIC_PLACEHOLDER_REGEX = Regex("\\[(?:Your Name|Recipient|Name|Company|Date|Insert|Sender)\\]", RegexOption.IGNORE_CASE)

    fun validate(
        rawTranscript: String,
        formattedText: String,
        protectedTokens: Set<String> = emptySet()
    ): ValidationResult {
        if (formattedText.isBlank()) {
            return ValidationResult.Invalid("Formatted text is empty or blank.")
        }

        val cleanedOnly = formattedText.filter { it.isLetterOrDigit() }
        if (cleanedOnly.isBlank()) {
            return ValidationResult.Invalid("Formatted text contains only punctuation.")
        }

        // 1. Check for chatty assistant prefixes
        for (pattern in CHATTY_PREAMBLE_PATTERNS) {
            if (pattern.containsMatchIn(formattedText.trim())) {
                return ValidationResult.Invalid("Output contains conversational AI preamble.")
            }
        }

        // 2. Reject hallucinated synthetic headers (e.g. "To:", "Subject:") unless explicitly dictated
        if (SYNTHETIC_HEADER_REGEX.containsMatchIn(formattedText)) {
            val rawLower = rawTranscript.lowercase()
            if (!rawLower.contains("subject") && !rawLower.contains("to:") && !rawLower.contains("from:")) {
                return ValidationResult.Invalid("Output hallucinated synthetic email/memo headers (To/Subject).")
            }
        }

        // 3. Reject hallucinated sign-offs unless explicitly dictated
        if (SYNTHETIC_CLOSING_REGEX.containsMatchIn(formattedText)) {
            val rawLower = rawTranscript.lowercase()
            if (!rawLower.contains("regards") && !rawLower.contains("sincerely") && !rawLower.contains("faithfully")) {
                return ValidationResult.Invalid("Output hallucinated synthetic email sign-off.")
            }
        }

        // 4. Reject placeholder brackets like "[Your Name]"
        if (SYNTHETIC_PLACEHOLDER_REGEX.containsMatchIn(formattedText)) {
            return ValidationResult.Invalid("Output hallucinated placeholder template.")
        }

        // 5. Sanity check against hallucinated answers or rewrites
        val rawWords = rawTranscript.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val formattedWords = formattedText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

        if (rawWords.size >= 4 && formattedWords.size > (rawWords.size * 1.5 + 4)) {
            return ValidationResult.Invalid("Output appears to answer/expand the query rather than formatting dictation.")
        }

        // 6. Check that critical protected tokens (if specified) were not dropped
        for (token in protectedTokens) {
            if (rawTranscript.contains(token, ignoreCase = true) &&
                !formattedText.contains(token, ignoreCase = true)
            ) {
                return ValidationResult.Invalid("Protected token '$token' was omitted.")
            }
        }

        return ValidationResult.Valid
    }
}
