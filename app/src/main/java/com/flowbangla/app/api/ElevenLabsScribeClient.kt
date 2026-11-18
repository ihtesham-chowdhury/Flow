package com.flowbangla.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * ElevenLabs Scribe (scribe_v1) Speech-to-Text Client.
 * World-class accuracy on Bangladesh Bengali (Dhaka/Sylheti/Standard) and English!
 * Endpoint: POST https://api.elevenlabs.io/v1/speech-to-text
 */
class ElevenLabsScribeClient(private val http: OkHttpClient) {

    fun transcribeAudio(audioFile: File, rawApiKey: String): String {
        val cleanKey = rawApiKey.trim()
        val mime = if (audioFile.extension.lowercase() == "wav") "audio/wav" else "audio/m4a"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model_id", "scribe_v1")
            .addFormDataPart("tag_audio_events", "false")
            .build()

        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", cleanKey)
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("ElevenLabs HTTP ${resp.code}: ${raw.take(140)}")
            }
            return JSONObject(raw).optString("text", "").trim()
        }
    }
}
