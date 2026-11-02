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
    val needsLeadingSpace: Boolean = false
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

        val fullText = node.text?.toString() ?: ""
        val selStart = node.textSelectionStart

        val textBeforeCursor = when {
            selStart in 1..fullText.length -> fullText.substring(0, selStart).takeLast(120)
            fullText.isNotEmpty() -> fullText.takeLast(120)
            else -> ""
        }

        val needsLeadingSpace = textBeforeCursor.isNotEmpty() && !textBeforeCursor.last().isWhitespace()

        val isMidSentenceContinuation = if (textBeforeCursor.isNotBlank()) {
            val lastNonWhitespace = textBeforeCursor.trimEnd().last()
            lastNonWhitespace !in listOf('।', '॥', '.', '?', '!', '\n')
        } else false

        return ActiveFieldContext(
            appCategory = appCategory,
            packageName = packageName,
            textBeforeCursor = textBeforeCursor,
            isMidSentenceContinuation = isMidSentenceContinuation,
            needsLeadingSpace = needsLeadingSpace
        )
    }
}
