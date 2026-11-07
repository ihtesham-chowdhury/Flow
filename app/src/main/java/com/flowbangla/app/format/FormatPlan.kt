package com.flowbangla.app.format

/**
 * Resolved formatting plan containing document classification,
 * user preferences, protected vocabulary tokens, and cleanup level.
 */
data class FormatPlan(
    val documentType: DocumentType = DocumentType.GENERAL_DICTATION,
    val numeralPreference: NumeralPreference = NumeralPreference.WESTERN,
    val outputScript: OutputScript = OutputScript.AS_SPOKEN,
    val protectedTokens: Set<String> = emptySet(),
    val isAutoDetected: Boolean = true,
    val cleanupLevel: AutoCleanupLevel = AutoCleanupLevel.MEDIUM,
    val appCategory: String = "OTHER"
)
