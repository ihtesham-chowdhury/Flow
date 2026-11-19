package com.flowbangla.app.model

enum class CloudPolishProvider(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val endpointUrl: String,
    val freeQuotaSummary: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini (Flash 3.5 / 3.8)",
        defaultModel = "gemini-3.5-flash-lite",
        endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/",
        freeQuotaSummary = "Reuses your existing Gemini API key (Free Tier in Google AI Studio)"
    ),
    GROQ(
        id = "groq",
        displayName = "GroqCloud (GPT-OSS 20B / 120B)",
        defaultModel = "openai/gpt-oss-20b",
        endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
        freeQuotaSummary = "Fast OpenAI-compatible inference with gpt-oss-20b and 120b"
    ),
    CEREBRAS(
        id = "cerebras",
        displayName = "Cerebras Inference (Ultra-Fast GPT-OSS 120B)",
        defaultModel = "gpt-oss-120b",
        endpointUrl = "https://api.cerebras.ai/v1/chat/completions",
        freeQuotaSummary = "~2,000 tokens/sec instant response with gpt-oss-120b"
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter (Unified Free Models Router)",
        defaultModel = "openrouter/free",
        endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
        freeQuotaSummary = "Routes automatically across free-tier community models"
    );

    companion object {
        fun fromId(id: String?): CloudPolishProvider = entries.find { it.id == id } ?: GEMINI
    }
}
