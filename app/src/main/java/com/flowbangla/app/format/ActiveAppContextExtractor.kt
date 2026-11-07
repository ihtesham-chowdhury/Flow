package com.flowbangla.app.format

import android.view.accessibility.AccessibilityNodeInfo

enum class AppCategory(val id: String, val labelEn: String) {
    PERSONAL_MESSAGING("personal_messaging", "Personal Chat"),
    WORK_MESSAGING("work_messaging", "Work Messaging"),
    EMAIL("email", "Email"),
    NOTES_AND_DOCS("notes_and_docs", "Notes & Documents"),
    OTHER("other", "General App")
}

data class ActiveFieldContext(
    val appCategory: AppCategory = AppCategory.OTHER,
    val packageName: String = "",
    val textBeforeCursor: String = "",
    val isMidSentenceContinuation: Boolean = false,
    val needsLeadingSpace: Boolean = false,
    val selectedText: String = "",
    val hasSelectedText: Boolean = false,
    val screenProperNouns: List<String> = emptyList()
)

object ActiveAppContextExtractor {

    private val SENSITIVE_BANKING_PACKAGES = listOf(
        "bkash", "nagad", "rocket", "upay", "bank", "wallet", "binance", "payoneer"
    )

    private val PERSONAL_MESSAGING_PACKAGES = listOf(
        "whatsapp", "telegram", "facebook.orca", "viber", "discord", "signal",
        "messaging", "mms", "im.vector", "com.google.android.apps.messaging"
    )

    private val WORK_MESSAGING_PACKAGES = listOf(
        "slack", "teams", "dynamite", "lark", "skype"
    )

    private val EMAIL_PACKAGES = listOf(
        "gmail", "gm", "outlook", "mail", "thunderbird", "k9"
    )

    private val NOTES_PACKAGES = listOf(
        "keep", "notes", "note", "docs", "document", "notion", "evernote", "onenote", "obsidian"
    )

    fun extract(node: AccessibilityNodeInfo?): ActiveFieldContext {
        if (node == null) return ActiveFieldContext()

        // Privacy rule 1: Never inspect text from password fields
        if (node.isPassword) {
            return ActiveFieldContext(AppCategory.OTHER, "", "", isMidSentenceContinuation = false, needsLeadingSpace = false)
        }

        val packageName = node.packageName?.toString()?.lowercase() ?: ""

        // Privacy rule 2: Never inspect text from banking/financial apps
        if (SENSITIVE_BANKING_PACKAGES.any { packageName.contains(it) }) {
            return ActiveFieldContext(AppCategory.OTHER, packageName, "", isMidSentenceContinuation = false, needsLeadingSpace = false)
        }

        val appCategory = when {
            PERSONAL_MESSAGING_PACKAGES.any { packageName.contains(it) } -> AppCategory.PERSONAL_MESSAGING
            WORK_MESSAGING_PACKAGES.any { packageName.contains(it) } -> AppCategory.WORK_MESSAGING
            EMAIL_PACKAGES.any { packageName.contains(it) } -> AppCategory.EMAIL
            NOTES_PACKAGES.any { packageName.contains(it) } -> AppCategory.NOTES_AND_DOCS
            else -> AppCategory.OTHER
        }

        val isShowingHint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.isShowingHintText
        } else {
            false
        }
        val hintString = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val rawText = node.text?.toString().orEmpty()
        val fullText = if (isShowingHint || (hintString.isNotEmpty() && rawText == hintString)) {
            ""
        } else {
            rawText
        }
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd

        val hasSelectedText = selStart >= 0 && selEnd > selStart && selEnd <= fullText.length
        val selectedText = if (hasSelectedText) {
            fullText.substring(selStart, selEnd).trim()
        } else {
            ""
        }

        // Reliable cursor detection:
        // Only inspect text before cursor if selStart is confirmed to be > 0.
        // If selStart is 0 (start of field/line) or -1 (unreported cursor or empty field),
        // we DO NOT have confirmed text before cursor.
        val textBeforeCursor = if (selStart > 0 && selStart <= fullText.length) {
            fullText.substring(0, selStart).takeLast(120)
        } else {
            ""
        }

        // Needs leading space ONLY if confirmed preceding text exists and does NOT end in whitespace or newline
        val needsLeadingSpace = textBeforeCursor.isNotEmpty() &&
                !textBeforeCursor.last().isWhitespace() &&
                textBeforeCursor.last() != '\n' &&
                textBeforeCursor.last() != '\r'

        val trimmedPre = textBeforeCursor.trimEnd()
        val isPunctuationEmoji = if (trimmedPre.isNotEmpty()) {
            trimmedPre.endsWith("‼️") || trimmedPre.endsWith("⁉️") ||
            trimmedPre.endsWith("❗") || trimmedPre.endsWith("❕") ||
            trimmedPre.endsWith("❓") || trimmedPre.endsWith("❔")
        } else false

        val isMidSentenceContinuation = if (trimmedPre.isNotBlank()) {
            if (isPunctuationEmoji) {
                false
            } else {
                val lastChar = trimmedPre.last()
                if (lastChar in listOf('"', '”', '’') && trimmedPre.length > 1) {
                    val charBeforeQuote = trimmedPre[trimmedPre.length - 2]
                    charBeforeQuote !in listOf('।', '॥', '.', '?', '!', '\n', '\r')
                } else {
                    lastChar !in listOf('।', '॥', '.', '?', '!', '\n', '\r')
                }
            }
        } else false

        // Extract visible proper nouns from the input field for acoustic biasing
        val screenProperNouns = if (fullText.isNotBlank()) {
            Regex("""\b[A-Z][a-z]{2,20}\b""").findAll(fullText)
                .map { it.value }
                .filter { it !in listOf("The", "And", "This", "That", "There", "Here", "What", "When", "Where", "How", "Why", "You", "Your", "Please") }
                .distinct()
                .take(20)
                .toList()
        } else {
            emptyList()
        }

        return ActiveFieldContext(
            appCategory = appCategory,
            packageName = packageName,
            textBeforeCursor = textBeforeCursor,
            isMidSentenceContinuation = isMidSentenceContinuation,
            needsLeadingSpace = needsLeadingSpace,
            selectedText = selectedText,
            hasSelectedText = hasSelectedText && selectedText.isNotEmpty(),
            screenProperNouns = screenProperNouns
        )
    }
}
