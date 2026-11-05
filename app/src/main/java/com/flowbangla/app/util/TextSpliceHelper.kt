package com.flowbangla.app.util

data class SpliceResult(
    val combinedText: String,
    val newCursorPos: Int
)

object TextSpliceHelper {

    private val PUNCTUATION_CHARS = setOf('.', ',', '!', '?', '।', ';', ':', ')', ']', '}')

    fun spliceText(
        actualExisting: String,
        newText: String,
        selStart: Int = -1,
        selEnd: Int = -1
    ): SpliceResult {
        if (actualExisting.isEmpty()) {
            return SpliceResult(newText, newText.length)
        }
        if (selStart >= 0 && selEnd >= selStart && selEnd <= actualExisting.length) {
            val prefix = actualExisting.substring(0, selStart)
            val suffix = actualExisting.substring(selEnd)
            val needsLeadingSpace = prefix.isNotEmpty() && !prefix.last().isWhitespace() && !newText.first().isWhitespace()
            val leadingSeparator = if (needsLeadingSpace) " " else ""
            val needsTrailingSpace = suffix.isNotEmpty() && !suffix.first().isWhitespace() && !newText.last().isWhitespace() &&
                suffix.first() !in PUNCTUATION_CHARS
            val trailingSeparator = if (needsTrailingSpace) " " else ""
            val combined = prefix + leadingSeparator + newText + trailingSeparator + suffix
            return SpliceResult(combined, (prefix + leadingSeparator + newText).length)
        }
        val needsSpace = !actualExisting.last().isWhitespace() && !newText.first().isWhitespace()
        val separator = if (needsSpace) " " else ""
        val combined = actualExisting + separator + newText
        return SpliceResult(combined, combined.length)
    }

    fun resolveEffectiveText(rawExisting: String, isHint: Boolean, hintText: String = ""): String {
        return if (isHint || (hintText.isNotEmpty() && rawExisting == hintText)) {
            ""
        } else {
            rawExisting
        }
    }
}
