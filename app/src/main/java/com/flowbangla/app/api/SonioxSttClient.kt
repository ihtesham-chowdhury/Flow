package com.flowbangla.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Soniox Speech-to-Text Client (stt-async-v5).
 * Ultra-accurate Bengali and English async transcription ($0.10/hr).
 * Endpoint: POST https://api.soniox.com/v1/transcribe
 */
class SonioxSttClient(private val http: OkHttpClient) {

    fun transcribeAudio(audioFile: File, rawApiKey: String): String {
        val cleanKey = rawApiKey.trim()
        val mime = if (audioFile.extension.lowercase() == "wav") "audio/wav" else "audio/m4a"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model", "stt-async-v5")
            .addFormDataPart("language_hints", JSONArray().put("bn").put("en").toString())
            .build()

        val req = Request.Builder()
            .url("https://api.soniox.com/v1/transcribe")
            .header("Authorization", "Bearer $cleanKey")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw FlowApiException(resp.code, ModelCatalog.mapHttpToErrorCategory(resp.code, raw), raw.take(140))
            }
            val json = JSONObject(raw)
            return json.optString("text", json.optString("transcript", "")).trim()
        }
    }
}
