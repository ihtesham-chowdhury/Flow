package com.flowbangla.app.format

enum class RecognitionSource(val label: String) {
    GEMINI_LIVE_STREAM("Gemini Live Stream"),
    GEMINI_TRANSCRIBE("Gemini Transcribe"),
    GROQ_WHISPER("Groq Whisper Large V3"),
    ANDROID_STT("On-Device Android STT"),
    UNKNOWN("Unknown")
}

enum class FormattingSource(val label: String) {
    GEMINI_SMART_SINGLE_PASS("Gemini Smart Mode (Single Pass)"),
    GEMINI_CLOUD("Gemini Format"),
    GROQ_CLOUD("Groq Format"),
    CEREBRAS_CLOUD("Cerebras Format"),
    OPENROUTER_CLOUD("OpenRouter Format"),
    LOCAL_LLM("Local Gemma LLM"),
    LOCAL_RULES("Local Rules"),
    RAW_FALLBACK("Raw Transcript")
}

data class FormattingResult(
    val rawTranscript: String,
    val formattedText: String,
    val documentType: DocumentType,
    val recognitionSource: RecognitionSource,
    val formattingSource: FormattingSource,
    val latencyMs: Long = 0,
    val fallbackUsed: Boolean = false
) {
    val displaySummary: String
        get() = "${recognitionSource.label} + ${formattingSource.label}"
}
