package com.flowbangla.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Deepgram Nova-3 Multilingual Speech-to-Text Client.
 * Ultra-low latency (<150ms) with $200 free tier credit!
 * Endpoint: POST https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&detect_language=true
 */
class DeepgramNovaClient(private val http: OkHttpClient) {

    fun transcribeAudio(audioFile: File, rawApiKey: String): String {
        val cleanKey = rawApiKey.trim()
        val mime = if (audioFile.extension.lowercase() == "wav") "audio/wav" else "audio/m4a"

        val req = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&detect_language=true")
            .header("Authorization", "Token $cleanKey")
            .header("Content-Type", mime)
            .post(audioFile.asRequestBody(mime.toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Deepgram HTTP ${resp.code}: ${raw.take(140)}")
            }
            return JSONObject(raw)
                .optJSONObject("results")
                ?.optJSONArray("channels")
                ?.optJSONObject(0)
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optString("transcript", "")
                .orEmpty()
                .trim()
        }
    }
}
