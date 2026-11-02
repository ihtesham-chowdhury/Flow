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
        displayName = "GroqCloud (Llama 3.3 70B / 3.1 8B)",
        defaultModel = "llama-3.3-70b-versatile",
        endpointUrl = "https://api.groq.com/openai/v1/chat/completions",
        freeQuotaSummary = "30 RPM; 1,000 RPD on Llama 3.3 70B or 14,400 RPD on Llama 3.1 8B"
    ),
    CEREBRAS(
        id = "cerebras",
        displayName = "Cerebras Inference (Ultra-Fast Llama 3.1 8B)",
        defaultModel = "llama3.1-8b",
        endpointUrl = "https://api.cerebras.ai/v1/chat/completions",
        freeQuotaSummary = "~2,000 tokens/sec instant response; generous free developer tier"
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
