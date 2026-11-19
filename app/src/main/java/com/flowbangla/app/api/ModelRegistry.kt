package com.flowbangla.app.api

import android.content.Context
import org.json.JSONObject

object ModelRegistry {
    const val BUNDLED_JSON = """{
  "schema": 1,
  "updated": "2026-11-20",
  "knownRetired": [
    "llama-3.3-70b-versatile",
    "llama-3.1-8b-instant",
    "llama-3.3-70b",
    "llama3.1-8b",
    "llama3-70b-8192",
    "llama3-8b-8192",
    "gemini-1.5-flash",
    "gemini-1.5-pro",
    "gemini-2.0-flash",
    "gemini-2.0-flash-exp",
    "gemini-3.5-flash",
    "scribe_v1"
  ],
  "providers": {
    "sarvam": {
      "asr": [{ "id": "saaras:v3", "fallbackId": "saaras:v4", "langs": ["bn", "en", "mixed"], "mode": "codemix" }]
    },
    "soniox": {
      "asr": [{ "id": "stt-async-v5", "realtimeId": "stt-rt-v5", "langs": ["bn", "en", "mixed"] }]
    },
    "gemini": {
      "asr": [{ "id": "gemini-3.5-transcribe", "langs": ["bn", "en", "mixed"], "limits": { "rpm": 2, "rpd": 25 } }],
      "polish": [{ "id": "gemini-3.5-flash-lite" }, { "id": "gemini-3.6-flash" }, { "id": "gemini-3.8-flash" }]
    },
    "groq": {
      "asr": [{ "id": "whisper-large-v3-turbo", "langs": ["en"], "limits": { "rpm": 20, "rpd": 2000, "audioSecPerHour": 7200 } }],
      "polish": [{ "id": "openai/gpt-oss-20b", "tier": "fast" }, { "id": "openai/gpt-oss-120b", "tier": "quality" }]
    },
    "cerebras": {
      "polish": [{ "id": "gpt-oss-120b", "ctxCap": 8000 }]
    },
    "elevenlabs": {
      "asr": [{ "id": "scribe_v3", "fallbackId": "scribe_v2", "langs": ["bn", "en", "mixed"], "batchOnly": true }]
    },
    "deepgram": {
      "asr": [{ "id": "nova-3", "langs": ["bn", "en", "mixed"] }]
    }
  }
}"""

    private var cachedJson: JSONObject = JSONObject(BUNDLED_JSON)

    fun init(context: Context? = null) {
        if (context != null) {
            try {
                val assetStr = context.assets.open("models_registry.json").bufferedReader().use { it.readText() }
                if (assetStr.isNotBlank()) {
                    cachedJson = JSONObject(assetStr)
                }
            } catch (_: Throwable) {}
        }
    }

    fun getKnownRetiredModels(): Set<String> {
        val arr = cachedJson.optJSONArray("knownRetired") ?: return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) set.add(s.lowercase())
        }
        return set
    }

    fun isModelRetired(modelId: String): Boolean {
        return getKnownRetiredModels().contains(modelId.trim().lowercase())
    }

    fun getActiveModelId(provider: String, task: String, fallback: String): String {
        val provObj = cachedJson.optJSONObject("providers")?.optJSONObject(provider.lowercase()) ?: return fallback
        val list = provObj.optJSONArray(task.lowercase()) ?: return fallback
        val first = list.optJSONObject(0) ?: return fallback
        return first.optString("id", fallback)
    }
}
