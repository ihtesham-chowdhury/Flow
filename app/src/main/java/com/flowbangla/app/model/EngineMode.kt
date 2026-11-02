package com.flowbangla.app.model

enum class EngineMode(
    val id: String,
    val titleEn: String,
    val titleBn: String,
    val descEn: String,
    val descBn: String
) {
    CLOUD_AUDIO(
        id = "cloud_audio",
        titleEn = "Cloud Audio (Gemini Multimodal)",
        titleBn = "ক্লাউড অডিও (জেমিনি মাল্টিমোডাল)",
        descEn = "Sends compressed audio directly to Gemini 2.0 Flash. Best for heavy Banglish code-switching.",
        descBn = "সরাসরি অডিও জেমিনিতে পাঠায়। বাংলিশ এবং মিশ্র ভাষার জন্য সেরা।"
    ),
    HYBRID_CLOUD_POLISH(
        id = "hybrid_cloud_polish",
        titleEn = "Hybrid Turbo (On-Device STT + Cloud Polish)",
        titleBn = "হাইব্রিড টার্বো (ফোন STT + ক্লাউড পলিশ)",
        descEn = "Transcribes speech on your phone using Gboard's engine, then formats text in ~250ms via Cloud LLM (Gemini, Groq, Cerebras, or OpenRouter).",
        descBn = "ফোনের ভেতরেই কথা টেক্সটে রূপান্তর করে এবং ক্লাউড LLM দিয়ে দ্রুত দাড়ি-কমা ও ফরম্যাটিং ঠিক করে।"
    ),
    OFFLINE_LIGHTWEIGHT(
        id = "offline_lightweight",
        titleEn = "Offline Lightweight (Zero Download)",
        titleBn = "অফলাইন লাইটওয়েট (ডাউনলোড ছাড়া)",
        descEn = "100% offline using Android's built-in speech engine + instant local filler removal & punctuation rules. Uses 0 MB extra RAM.",
        descBn = "কোনো ইন্টারনেট বা বড় মডেল ডাউনলোড ছাড়াই ফোনের নিজস্ব ইঞ্জিনে দ্রুত অফলাইন টাইপিং।"
    ),
    OFFLINE_LOCAL_LLM(
        id = "offline_local_llm",
        titleEn = "Offline AI Model (On-Device LLM)",
        titleBn = "অফলাইন এআই মডেল (অন-ডিভাইস LLM)",
        descEn = "100% offline STT + downloaded Gemma 3 (1B) model via LiteRT-LM for full on-device AI rewriting (Recommended for 8GB+ RAM phones).",
        descBn = "সম্পূর্ণ অফলাইনে এআই ফরম্যাটিংয়ের জন্য ফোনে Gemma 3 মডেল ডাউনলোড করে কাজ করে (৮ জিবি+ র‍্যামের ফোনের জন্য)।"
    );

    companion object {
        fun fromId(id: String?): EngineMode = entries.find { it.id == id } ?: CLOUD_AUDIO
    }
}
