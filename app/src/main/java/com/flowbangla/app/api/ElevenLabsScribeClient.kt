package com.flowbangla.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * ElevenLabs Scribe (scribe_v3 / scribe_v2) Speech-to-Text Client.
 * World-class accuracy on Bangladesh Bengali (Dhaka/Sylheti/Standard) and English!
 * Endpoint: POST https://api.elevenlabs.io/v1/speech-to-text
 */
class ElevenLabsScribeClient(private val http: OkHttpClient) {

    fun transcribeAudio(audioFile: File, rawApiKey: String): String {
        val cleanKey = rawApiKey.trim()
        val mime = if (audioFile.extension.lowercase() == "wav") "audio/wav" else "audio/m4a"

        val candidateModels = listOf("scribe_v3", "scribe_v2")
        var lastError = ""

        for (modelId in candidateModels) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
                .addFormDataPart("model_id", modelId)
                .addFormDataPart("tag_audio_events", "false")
                .build()

            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/speech-to-text")
                .header("xi-api-key", cleanKey)
                .post(body)
                .build()

            try {
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        return JSONObject(raw).optString("text", "").trim()
                    } else {
                        lastError = "ElevenLabs HTTP ${resp.code}: ${raw.take(140)}"
                    }
                }
            } catch (t: Throwable) {
                lastError = "${t.message}"
            }
        }
        throw RuntimeException(lastError.ifEmpty { "ElevenLabs Scribe transcription failed" })
    }
}
