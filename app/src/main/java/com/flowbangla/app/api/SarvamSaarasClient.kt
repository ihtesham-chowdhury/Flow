package com.flowbangla.app.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

class SarvamSaarasClient(private val http: OkHttpClient) {

    fun transcribeAudio(audioFile: File, rawApiKey: String, languageCode: String = "bn-IN"): String {
        val cleanKey = rawApiKey.trim()
        val mime = if (audioFile.extension.lowercase() == "wav") "audio/wav" else "audio/m4a"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model", "saaras:v3")
            .addFormDataPart("mode", "codemix") // Native Bengali-English code-mixed output!
            .addFormDataPart("language_code", languageCode)
            .build()

        val req = Request.Builder()
            .url("https://api.sarvam.ai/speech-to-text")
            .header("api-subscription-key", cleanKey)
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw FlowApiException(resp.code, ModelCatalog.mapHttpToErrorCategory(resp.code, raw), raw.take(140))
            }
            return JSONObject(raw).optString("transcript", "").trim()
        }
    }
}
