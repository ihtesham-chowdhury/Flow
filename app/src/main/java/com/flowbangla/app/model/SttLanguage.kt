package com.flowbangla.app.model

enum class SttLanguage(
    val localeTag: String,
    val badgeText: String,
    val labelEn: String,
    val labelBn: String
) {
    BENGALI(
        localeTag = "bn-BD",
        badgeText = "বাং",
        labelEn = "Bengali (বাংলা - bn-BD)",
        labelBn = "বাংলা (bn-BD)"
    ),
    ENGLISH(
        localeTag = "en-US",
        badgeText = "EN",
        labelEn = "English (en-US)",
        labelBn = "ইংরেজি (en-US)"
    );

    fun toggle(): SttLanguage = if (this == BENGALI) ENGLISH else BENGALI

    companion object {
        fun fromTag(tag: String?): SttLanguage = entries.find { it.localeTag == tag } ?: BENGALI
    }
}
