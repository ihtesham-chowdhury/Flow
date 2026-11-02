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

        // Check for chatty assistant prefixes
        for (pattern in CHATTY_PREAMBLE_PATTERNS) {
            if (pattern.containsMatchIn(formattedText.trim())) {
                return ValidationResult.Invalid("Output contains conversational AI preamble.")
            }
        }

        // Sanity check against hallucinated answers:
        // Dictation formatting should never expand word count by > 4x unless raw transcript is extremely short (< 4 words)
        val rawWords = rawTranscript.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val formattedWords = formattedText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

        if (rawWords.size in 4..40 && formattedWords.size > rawWords.size * 3.5) {
            return ValidationResult.Invalid("Output appears to answer the query rather than formatting dictation.")
        }

        // Check that critical protected tokens (if specified) were not dropped
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
