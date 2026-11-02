package com.flowbangla.app.format

/**
 * Intelligent Document-Type taxonomy for Flow dictation.
 * The formatter infers the document type from the speaker's phrasing, structure,
 * and context, adapting paragraphing, punctuation, and layout accordingly.
 */
enum class DocumentType(val id: String, val displayNameEn: String, val displayNameBn: String) {
    CASUAL_MESSAGE("casual_message", "Casual Message", "ছোট বার্তা / চ্যাট"),
    LONG_MESSAGE("long_message", "Long Message", "বিস্তারিত বার্তা"),
    EMAIL("email", "Email", "ইমেইল"),
    FORMAL_LETTER("formal_letter", "Formal Letter / Application", "আনুষ্ঠানিক পত্র / আবেদন"),
    PERSONAL_NOTE("personal_note", "Personal Note", "ব্যক্তিগত নোট"),
    MEETING_NOTES("meeting_notes", "Meeting Notes", "মিটিং নোট"),
    TASK_LIST("task_list", "Task / Checklist", "কাজের তালিকা / চেকলিস্ট"),
    NUMBERED_STEPS("numbered_steps", "Numbered Steps", "ধারাবাহিক ধাপ (১, ২, ৩)"),
    SOCIAL_POST("social_post", "Social Post / Caption", "সোশ্যাল পোস্ট / ক্যাপশন"),
    QUESTION_OR_QUERY("question_or_query", "Question / Query", "প্রশ্ন / সার্চ কোয়েরি"),
    TECHNICAL_TEXT("technical_text", "Technical / Code", "প্রযুক্তিগত / কোড"),
    ADDRESS_OR_CONTACT("address_or_contact", "Address / Contact Info", "ঠিকানা / যোগাযোগের তথ্য"),
    GENERAL_DICTATION("general_dictation", "General Dictation", "সাধারণ ডিক্টেশন");

    companion object {
        fun fromId(id: String): DocumentType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GENERAL_DICTATION
        }
    }
}
