package com.flowbangla.app.format

/**
 * Numeral presentation preference.
 */
enum class NumeralPreference(val id: String, val labelEn: String, val labelBn: String) {
    WESTERN("western", "Western (1, 2, 3)", "ইংরেজি সংখ্যা (1, 2, 3)"),
    BENGALI("bengali", "Bengali (১, ২, ৩)", "বাংলা সংখ্যা (১, ২, ৩)");

    companion object {
        fun fromId(id: String): NumeralPreference {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: WESTERN
        }
    }
}

/**
 * Output script rendering mode for code-switched or Bengali dictation.
 */
enum class OutputScript(val id: String, val labelEn: String, val labelBn: String) {
    AS_SPOKEN("as_spoken", "As Spoken (Bengali + Latin English)", "উচ্চারণ অনুযায়ী (বাংলা + ইংরেজি)"),
    BENGALI_SCRIPT("bengali_script", "Bengali Script Priority", "বাংলা হরফ অগ্রাধিকার"),
    ROMANIZED_BANGLA("romanized", "Romanized Bangla", "রোমানাইজড বাংলা");

    companion object {
        fun fromId(id: String): OutputScript {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AS_SPOKEN
        }
    }
}

/**
 * Explicit formatting mode override by user.
 * AUTO is default (Flow detects document type automatically).
 */
enum class FormattingMode(val id: String, val labelEn: String, val labelBn: String) {
    AUTO("auto", "Auto Detect (Recommended)", "স্বয়ংক্রিয় শনাক্তকরণ (প্রস্তাবিত)"),
    CASUAL_MESSAGE("casual_message", "Casual Messaging", "ছোট বার্তা / চ্যাট"),
    NOTES("notes", "Notes & Thoughts", "নোট ও ভাবনা"),
    FORMAL_LETTER("formal_letter", "Formal / Letter", "আনুষ্ঠানিক পত্র"),
    TASK_LIST("task_list", "Task List / Bullets", "কাজের তালিকা / বুলেট পয়েন্ট"),
    EMAIL("email", "Email", "ইমেইল");

    companion object {
        fun fromId(id: String): FormattingMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}
