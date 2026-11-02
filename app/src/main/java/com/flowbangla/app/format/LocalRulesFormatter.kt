package com.flowbangla.app.format

/**
 * Deterministic local offline formatter.
 * Runs instantly on device with zero API latency and zero cost.
 */
object LocalRulesFormatter {

    fun format(rawTranscript: String, plan: FormatPlan): FormattedEnvelope {
        if (rawTranscript.isBlank()) {
            return FormattedEnvelope("", plan.documentType, "UNKNOWN", isParsedFromJson = false)
        }

        // Apply matra-safe Bengali & English cleaning, spoken commands, and punctuation
        val processed = TranscriptPostProcessor.process(
            text = rawTranscript,
            numeralPreference = plan.numeralPreference
        )

        // Add sentence capitalization for English words and proper ending punctuation if missing
        val finalized = applyBasicPunctuationAndCapitalization(processed)

        return FormattedEnvelope(
            formattedText = finalized,
            documentType = plan.documentType,
            detectedLanguage = "BANGLA_ENGLISH_MIXED",
            isParsedFromJson = false
        )
    }

    private fun applyBasicPunctuationAndCapitalization(text: String): String {
        if (text.isBlank()) return ""

        val lines = text.lines().map { line ->
            var l = line.trim()
            if (l.isNotBlank()) {
                // Capitalize first ASCII letter if applicable
                val firstChar = l.first()
                if (firstChar in 'a'..'z') {
                    l = firstChar.uppercaseChar() + l.substring(1)
                }

                // If line does not end with punctuation or bullet, add danda or period based on script
                val lastChar = l.last()
                if (lastChar !in listOf('।', '.', '?', '!', ':', ';', ',', '•', '-')) {
                    val hasBengali = l.any { it in '\u0980'..'\u09FF' }
                    l = if (hasBengali) "$l।" else "$l."
                }
            }
            l
        }

        return lines.joinToString("\n")
    }
}
