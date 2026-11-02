package com.flowbangla.app.polish

object LocalFillerCleaner {

    // Safe hesitation regex: strips "উম", "অ্যাঁ", "ইয়ে মানে", "মানে হচ্ছে" without deleting
    // lexical "মানে" in "এর মানে কী?" or expressive "আহা" in "আহা কী সুন্দর!"
    private val SAFE_BENGALI_HESITATION_REGEX = Regex(
        pattern = """(?<=^|\s)(উম+|অ্যাঁ+|ইয়ে মানে|মানে হচ্ছে|মানে হলো)(?=\s|,|$)""",
        option = RegexOption.IGNORE_CASE
    )

    private val ENGLISH_FILLERS_REGEX = Regex(
        pattern = """\b(um+|uh+|erm+|er+|ah+|you know|i mean|sort of Like)\b""",
        option = RegexOption.IGNORE_CASE
    )

    // Repeated consecutive word stutters (e.g., "আমি আমি যাব" -> "আমি যাব", "I I think" -> "I think")
    private val STUTTER_REGEX = Regex("""\b(\S+)(\s+\1\b)+""", RegexOption.IGNORE_CASE)

    // Common Bengali & English interrogative markers for question mark ('?') detection
    private val BENGALI_QUESTION_WORDS = listOf(
        "কী", "কি", "কেন", "কখন", "কোথায়", "কোথায়", "কীভাবে", "কিভাবে", "কেমন", "কার", "কাকে", "কত", "নাকি"
    )
    private val ENGLISH_QUESTION_PREFIXES = listOf(
        "what ", "why ", "when ", "where ", "who ", "whom ", "whose ", "which ", "how ",
        "is ", "are ", "was ", "were ", "do ", "does ", "did ", "can ", "could ", "will ", "would ", "should "
    )

    fun cleanAndPunctuate(rawText: String): String {
        if (rawText.isBlank()) return ""

        var text = rawText.trim()
            .replace(SAFE_BENGALI_HESITATION_REGEX, "")
            .replace(ENGLISH_FILLERS_REGEX, "")
            .replace(STUTTER_REGEX, "$1")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        if (text.isEmpty()) return rawText.trim()

        val containsBengaliScript = text.any { it in '\u0980'..'\u09FF' }
        val lowerText = text.lowercase()

        // Capitalize first letter if starts with Latin character
        if (text[0].isLowerCase()) {
            text = text[0].uppercaseChar() + text.substring(1)
        }
        // Capitalize standalone English "i"
        text = text.replace(Regex("""\bi\b"""), "I")

        // Check if already ends with terminal punctuation
        val lastChar = text.last()
        if (lastChar in charArrayOf('.', '?', '!', '।', '॥')) {
            return text
        }

        val isQuestion = if (containsBengaliScript) {
            BENGALI_QUESTION_WORDS.any { word ->
                Regex("""(?<=^|\s)$word(?=\s|$)""").containsMatchIn(text)
            }
        } else {
            ENGLISH_QUESTION_PREFIXES.any { lowerText.startsWith(it) }
        }

        return when {
            isQuestion -> "$text?"
            containsBengaliScript -> "$text।"
            else -> "$text."
        }
    }
}
