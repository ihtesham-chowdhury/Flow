package com.flowbangla.app.format

/**
 * Offline deterministic backtrack and self-correction resolver.
 * Used when running offline or as a deterministic preprocessing pass.
 */
object LocalBacktrackResolver {

    // Matches patterns like "brinjals, no no not brinjals but get some rice" or "brinjals no not brinjals, rice"
    private val EXPLICIT_NOT_REPLACEMENT_REGEX = Regex(
        pattern = """(\b[\p{L}\d_-]+\b)[,.]?\s+(?:(?:oh\s+)?no[\s,]*)+not\s+\1\s*(?:,?\s*(?:but\s+)?(?:get\s+)?(?:some\s+)?)?""",
        option = RegexOption.IGNORE_CASE
    )

    // Matches patterns like "Let's make a recipe... oh no wait let's make a grocery list"
    private val TOPIC_BACKTRACK_REGEX = Regex(
        pattern = """(?i)(?:let's\s+make\s+a\s+|make\s+a\s+|start\s+a\s+)?[\p{L}\d_-]+[.\s,]+(?:(?:oh\s+)?no[\s,]+wait|(?:oh\s+)?no[\s,]+no|no|wait|actually)[,\s]+(?:let's\s+make\s+a\s+|make\s+a\s+|let's\s+have\s+a\s+|let's\s+do\s+a\s+)?(?=grocery\s+list|to-do\s+list|meeting|email|note)""",
        option = RegexOption.IGNORE_CASE
    )

    // Matches value backtracks like "at 2 actually 3" or "at 2, no wait, 3"
    private val VALUE_BACKTRACK_REGEX = Regex(
        pattern = """\b(\d+)\s+(?:actually|(?:oh\s+)?no[\s,]+wait|no|sorry)\s+(\d+)\b""",
        option = RegexOption.IGNORE_CASE
    )

    // Matches Bengali: "বেগুন না না বেগুন লাগবে না চালের বস্তা এনো"
    private val BANGLA_ITEM_BACKTRACK_REGEX = Regex(
        pattern = """([\u0980-\u09FF]+)[,.]?\s+(?:না[\s,]*)+(\1)\s*(?:লাগবে\s+না|দরকার\s+নেই|বাদ\s+দাও)?\s*""",
        option = RegexOption.IGNORE_CASE
    )

    // Matches "scratch that", "never mind", "বাদ দাও"
    private val SIMPLE_SCRATCH_REGEX = Regex(
        pattern = """[^,.।?!;\n]+?[,.]?\s*\b(?:scratch that|never mind|বাদ দাও|থাক ওটা না)\b[,.]?\s*""",
        option = RegexOption.IGNORE_CASE
    )

    fun resolveOfflineBacktracks(input: String): String {
        if (input.isBlank()) return ""
        var result = input

        // 1. Bengali item backtrack
        result = BANGLA_ITEM_BACKTRACK_REGEX.replace(result) { "" }

        // 2. Explicit English not replacement
        result = EXPLICIT_NOT_REPLACEMENT_REGEX.replace(result) { "" }

        // 3. Topic backtrack
        result = TOPIC_BACKTRACK_REGEX.replace(result) { "" }

        // 4. Value backtrack ("at 2 actually 3" -> "at 3")
        result = VALUE_BACKTRACK_REGEX.replace(result) { match -> match.groupValues[2] }

        // 5. Scratch that / never mind
        result = SIMPLE_SCRATCH_REGEX.replace(result) { "" }

        // Clean up formatting artifacts (duplicate spaces, orphaned commas)
        result = result.replace(Regex("""\s+,\s*"""), ", ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        return result
    }
}
