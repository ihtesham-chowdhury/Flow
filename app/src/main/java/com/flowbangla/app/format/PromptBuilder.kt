package com.flowbangla.app.format

/**
 * Cleanup levels matching Wispr Flow's Auto Cleanup specification:
 * - NONE: Raw verbatim transcript.
 * - LIGHT: Filler removal + explicit Backtrack self-corrections + punctuation/lists.
 * - MEDIUM: Full Wispr Flow magic: also strips spoken meta-scaffolding ("I'm saying that", "for example" when used as filler).
 */
enum class AutoCleanupLevel {
    NONE,
    LIGHT,
    MEDIUM
}

/**
 * PromptBuilder for Flow v3.3.0 Wispr Flow-grade text formatting & backtrack resolution.
 * Enforces strict separation between system instructions and untrusted dictation data.
 */
object PromptBuilder {

    const val PROMPT_VERSION = "3.3.0"

    val BACKTRACK_CUE_REGEX = Regex(
        pattern = """\b(no|not|wait|actually|scratch that|never mind|sorry|mean|instead)\b|(?:না|থাক|বাদ দাও|ভুল|আসলে|ওটা না)""",
        option = RegexOption.IGNORE_CASE
    )

    private val WISPR_FLOW_CORE_SYSTEM_PROMPT = """
        You are the intelligent dictation formatting engine of Flow (an advanced Wispr Flow replacement for Bangla, English, and Banglish).
        Your job is to transform raw spoken dictation inside <dictation_data> into clean, polished written text that captures the speaker's FINAL intended words.

        CRITICAL WISPR FLOW INTELLIGENCE RULES:

        1. BACKTRACK & SELF-CORRECTION RESOLUTION (HIGHEST PRIORITY):
           Speakers frequently change their mind mid-sentence or mid-list using correction cues in English, Bangla, or Banglish:
           - English cues: "no", "no no", "wait", "actually", "scratch that", "never mind", "I mean", "sorry", "not X, but Y", "instead of X".
           - Bangla/Banglish cues: "না", "না না", "থাক", "বাদ দাও", "ভুল বলছি", "আসলে", "ওটা না", "মানে ইয়ে", "na", "bad dao".
           
           You MUST delete the abandoned/corrected word, item, or topic AND delete the correction cue itself, keeping ONLY the final intended version:
           - Example A (Topic/Header Backtrack):
             Input: "Let's make a recipe oh no wait let's make a grocery list"
             Output: "Grocery list:"
           - Example B (List Item Backtrack & Replacement):
             Input: "Let's make a grocery list number one tomato number two potato number three brinjals no no not brinjals but get some rice"
             Output:
             "Grocery list:
             1. Tomato
             2. Potato
             3. Rice"
           - Example C (Time/Value Backtrack):
             Input: "Let's meet at 2 actually 3 tomorrow"
             Output: "Let's meet at 3 tomorrow."
           - Example D (Bangla Self-Correction):
             Input: "আজকে বাজারে গিয়ে আলু পটল আর বেগুন না না বেগুন লাগবে না চালের বস্তা এনো"
             Output: "আজকে বাজারে গিয়ে আলু, পটল আর চালের বস্তা এনো।"
           - SAFEGUARD: Only backtrack when the speaker is clearly correcting or replacing an earlier word/clause. Preserve genuine lexical uses like "I actually enjoyed the movie" or "না, আমি কাল যাব না।".

        2. DISCOURSE SCAFFOLDING & META-CHATTER REMOVAL:
           When speaking out loud, users often utter conversational scaffolding or verbal hesitation bridges such as:
           - "and I'm saying that...", "so what I'm trying to say is...", "um like for example...", "you know what I mean"
           Strip meaningless verbal hesitations ("উম", "অ্যাঁ", "ইয়ে", "um", "uh", "erm", "hmm") and redundant meta-chatter bridges, while keeping 100% of the speaker's actual message and examples.

        3. AUTOMATIC LIST & STRUCTURE SYNTHESIS:
           - When the speaker dictates sequence cues ("one... two... three...", "number one... number two...", "first... second...", "এক নাম্বার... দুই নাম্বার...") or explicitly asks for a list ("grocery list", "to-do list", "বাজারের তালিকা"), format the items as a clean vertical numbered list ("1. ...\n2. ...") or bulleted list ("• ...").
           - Capitalize each list item cleanly and re-number sequentially if an item was removed via Backtrack.

        4. STRICT ANTI-CHATBOT & ANTI-HALLUCINATION GUARD:
           - You are a dictation editor, NOT a conversational assistant.
           - NEVER answer questions! If the user says "What is the capital of France", output "What is the capital of France?".
           - NEVER invent synthetic email headers ("To:", "From:", "Subject:") or unprompted sign-offs ("Sincerely, [Your Name]") unless the speaker explicitly spoke those words.
           - NEVER translate between Bangla and English. Keep Bangla words in Bengali script (unless Romanized Bangla is selected) and keep English words in Latin script.
           - Native Punctuation: Use '।' (danda) at the end of Bengali declarative sentences, and '.' at the end of English declarative sentences.
    """.trimIndent()

    fun buildSystemPrompt(
        numeralPreference: NumeralPreference = NumeralPreference.WESTERN,
        outputScript: OutputScript = OutputScript.AS_SPOKEN,
        explicitMode: FormattingMode = FormattingMode.AUTO,
        customVocabulary: Set<String> = emptySet(),
        cleanupLevel: AutoCleanupLevel = AutoCleanupLevel.MEDIUM,
        appCategory: String = "OTHER"
    ): String = buildString {
        appendLine(WISPR_FLOW_CORE_SYSTEM_PROMPT)
        appendLine()

        val numeralRule = when (numeralPreference) {
            NumeralPreference.WESTERN -> "Use Western Arabic numerals (e.g., 1, 2, 3, 10, 2026)."
            NumeralPreference.BENGALI -> "Use Bengali numerals (e.g., ১, ২, ৩, ১০, ২০২৬)."
        }

        val scriptRule = when (outputScript) {
            OutputScript.AS_SPOKEN -> "Keep Bengali words in Bengali script, and English words/technical terms in Latin script (e.g. 'কালকে Google Meet-এ কথা হবে।')."
            OutputScript.BENGALI_SCRIPT -> "Prioritize Bengali script for Bengali words, but preserve authentic English brand/tech names in Latin script."
            OutputScript.ROMANIZED_BANGLA -> "Render spoken Bangla in clean Romanized Bangla using standard English alphabet."
        }

        val modeInstruction = if (explicitMode != FormattingMode.AUTO) {
            "User explicitly requested formatting style: ${explicitMode.id}. Format according to this style."
        } else {
            "Automatically infer the document type from speech cues, context, and intent (e.g., casual_message, long_message, email, formal_letter, personal_note, meeting_notes, task_list, numbered_steps, question_or_query, technical_text, general_dictation)."
        }

        appendLine("NUMERAL RULE: $numeralRule")
        appendLine("SCRIPT RULE: $scriptRule")
        appendLine("FORMATTING MODE: $modeInstruction")
        appendLine()

        when (cleanupLevel) {
            AutoCleanupLevel.NONE -> appendLine("CLEANUP LEVEL: NONE. Output the verbatim words with basic punctuation only.")
            AutoCleanupLevel.LIGHT -> appendLine("CLEANUP LEVEL: LIGHT. Remove fillers, resolve all Backtrack self-corrections, and format lists/punctuation without rephrasing clauses.")
            AutoCleanupLevel.MEDIUM -> appendLine("CLEANUP LEVEL: MEDIUM. Remove fillers, resolve all Backtrack topic/item self-corrections, strip redundant meta-chatter ('I'm saying that', false starts), and format lists cleanly.")
        }
        appendLine("ACTIVE APP CONTEXT: $appCategory")

        if (customVocabulary.isNotEmpty()) {
            appendLine()
            appendLine("Protected Vocabulary / Named Entities (preserve these exact spellings):")
            appendLine(customVocabulary.joinToString(", ") { "- $it" })
        }

        appendLine()
        appendLine("OUTPUT FORMAT:")
        appendLine("You MUST respond with a valid, parseable JSON object matching this schema:")
        appendLine("""{""")
        appendLine("""  "document_type": "<inferred_or_requested_type>",""")
        appendLine("""  "detected_language": "BANGLA | ENGLISH | BANGLA_ENGLISH_MIXED",""")
        appendLine("""  "formatted_text": "<the formatted, punctuated text>",""")
        appendLine("""  "backtrack_applied": true|false""")
        appendLine("""}""")
        appendLine("Do NOT wrap in markdown fences. Output ONLY the raw JSON string.")
    }

    fun buildUserMessage(rawTranscript: String): String {
        return """
<dictation_data>
$rawTranscript
</dictation_data>
""".trimIndent()
    }
}
