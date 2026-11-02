package com.flowbangla.app.format

import java.text.Normalizer

/**
 * Matra-safe, Bengali grapheme cluster preserving post-processor for Flow dictations.
 * Normalizes Unicode, cleans whitespace, collapses duplicate punctuation, parses
 * spoken punctuation commands, and applies numeral preferences safely.
 */
object TranscriptPostProcessor {

    private const val BN = "\u0980-\u09FF"
    private const val WORD_CHARS = "$BN\\w"

    private val WESTERN_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    private val BENGALI_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun process(
        text: String,
        numeralPreference: NumeralPreference = NumeralPreference.WESTERN
    ): String {
        if (text.isBlank()) return ""

        var result = Normalizer.normalize(text, Normalizer.Form.NFC)

        // 1. Spoken layout & punctuation commands
        result = applySpokenCommands(result)

        // 2. Context-aware filler cleanup
        result = cleanFillers(result)

        // 3. Punctuation spacing & duplicate collapse
        result = cleanPunctuation(result)

        // 4. Numeral conversion according to preference
        result = convertNumerals(result, numeralPreference)

        // 5. Final whitespace normalization
        result = normalizeWhitespace(result)

        return result.trim()
    }

    private fun applySpokenCommands(input: String): String {
        var str = input

        // Multi-line commands
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(new paragraph|নতুন অনুচ্ছেদ|নতুন প্যারাগ্রাফ)(?![$WORD_CHARS])"), "\n\n")
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(new line|নতুন লাইন)(?![$WORD_CHARS])"), "\n")
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(bullet point|বুলেট পয়েন্ট|বুলেট পয়েন্ট)(?![$WORD_CHARS])"), "\n• ")

        // Punctuation commands
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(comma|কমা)(?![$WORD_CHARS])"), ",")
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(question mark|প্রশ্নবোধক চিহ্ন|প্রশ্নবোধক)(?![$WORD_CHARS])"), "?")
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(exclamation mark|বিস্ময়সূচক চিহ্ন|বিস্ময়সূচক চিহ্ন)(?![$WORD_CHARS])"), "!")

        // Dari for Bengali, period for English
        val normalizedDari1 = Normalizer.normalize("দাঁড়ি", Normalizer.Form.NFC)
        val normalizedDari2 = Normalizer.normalize("দাঁড়ি", Normalizer.Form.NFD)
        str = str.replace(Regex("(?<![$WORD_CHARS])($normalizedDari1|$normalizedDari2|দাঁড়ি|দাঁড়ি|দাড়ি|দাড়ি)(?![$WORD_CHARS])"), "।")
        str = str.replace(Regex("(?<![$WORD_CHARS])ফুল স্টপ(?![$WORD_CHARS])"), "।")
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(period|full stop)(?![$WORD_CHARS])"), ".")

        return str
    }

    private fun cleanFillers(input: String): String {
        var str = input

        // Standalone hesitation markers
        str = str.replace(Regex("(?i)(?<![$WORD_CHARS])(um+|uh+|er+|ah+)(?![$WORD_CHARS])"), "")
        str = str.replace(Regex("(?<![$WORD_CHARS])(উম+|আহ্?)(?![$WORD_CHARS])"), "")
        str = str.replace(Regex("(?<![$WORD_CHARS])(ইয়ে|ইয়ে)(?![$WORD_CHARS])"), "")

        // Preserve meaningful "এর মানে", "মানে কী", "মানেটা".
        // Only strip "মানে" when it is sentence-initial hesitation like "মানে, আমি যাব"
        str = str.replace(Regex("(^|[\\n।!?]\\s*)মানে\\s*,?\\s+"), "$1")

        // Collapse accidental immediate word stutters: "I I think" -> "I think"
        str = str.replace(Regex("(?i)\\b([A-Za-z]+)\\s+\\1\\b"), "$1")

        return str
    }

    private fun cleanPunctuation(input: String): String {
        var str = input

        // Remove space immediately preceding punctuation marks
        str = str.replace(Regex("\\s+([।.,?!:;])"), "$1")

        // Collapse duplicate consecutive punctuation marks
        str = str.replace(Regex("।+"), "।")
        str = str.replace(Regex("\\.{2,}"), ".")
        str = str.replace(Regex(",+"), ",")
        str = str.replace(Regex("\\?+"), "?")
        str = str.replace(Regex("!+"), "!")
        str = str.replace(Regex("([।.,?!])\\1+"), "$1")
        str = str.replace(Regex("([।])\\."), "$1")
        str = str.replace(Regex("\\.([।])"), "$1")

        // Ensure single space after punctuation (unless newline, quote, or end of string)
        str = str.replace(Regex("([।.,?!:;])([^\\s।.,?!:;\\n\"'”’])"), "$1 $2")

        // Bullet point formatting: ensure space after bullet
        str = str.replace(Regex("(?m)^•\\s*"), "• ")

        return str
    }

    private fun convertNumerals(input: String, preference: NumeralPreference): String {
        val sb = java.lang.StringBuilder(input.length)
        when (preference) {
            NumeralPreference.WESTERN -> {
                for (ch in input) {
                    val idx = BENGALI_DIGITS.indexOf(ch)
                    if (idx != -1) {
                        sb.append(WESTERN_DIGITS[idx])
                    } else {
                        sb.append(ch)
                    }
                }
            }
            NumeralPreference.BENGALI -> {
                for (ch in input) {
                    val idx = WESTERN_DIGITS.indexOf(ch)
                    if (idx != -1) {
                        sb.append(BENGALI_DIGITS[idx])
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun normalizeWhitespace(input: String): String {
        val lines = input.lines()
        val processedLines = lines.map { line ->
            // Replace tabs and multiple inline spaces with a single space
            line.replace(Regex("[ \\t]+"), " ").trim()
        }

        // Join lines and limit excessive consecutive newlines to maximum 2 (one blank line between paragraphs)
        val joined = processedLines.joinToString("\n")
        return joined.replace(Regex("\\n{3,}"), "\n\n")
    }
}
