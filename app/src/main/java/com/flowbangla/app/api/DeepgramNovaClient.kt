package com.flowbangla.app.api

import android.util.Log
import com.flowbangla.app.audio.AudioFileInspector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Deepgram Nova-3 Multilingual Speech-to-Text Client.
 * Ultra-low latency (<150ms) with $200 free tier credit!
 * Uses explicit Nova-3 language routing (en, bn, multi) and magic-byte MIME detection.
 */
class DeepgramNovaClient(private val http: OkHttpClient) {

    companion object {
        private const val TAG = "FlowDeepgram"

        fun buildLanguageQuery(speechLang: SpeechLang): String = when (speechLang) {
            SpeechLang.ENGLISH -> "language=en"
            SpeechLang.BANGLA -> "language=bn"
            SpeechLang.MIXED -> "language=multi"
        }
    }

    fun transcribeAudio(
        audioFile: File,
        rawApiKey: String,
        speechLang: SpeechLang = SpeechLang.MIXED
    ): String {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) {
            throw FlowApiException(401, ErrorCategory.AUTHENTICATION_FAILED, "Deepgram API key is empty.")
        }

        // 1. Always inspect actual magic bytes (never guess from filename extension!)
        val meta = AudioFileInspector.inspectAndValidate(audioFile)

        // 2. Use explicit Nova-3 language query param instead of unguided `detect_language=true`
        //    so short 2-second clips never mis-detect as French/Spanish!
        val langQuery = buildLanguageQuery(speechLang)

        val url = "https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&punctuate=true&$langQuery"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Token $cleanKey")
            .header("Content-Type", meta.verifiedMimeType)
            .post(audioFile.asRequestBody(meta.verifiedMimeType.toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            Log.i(TAG, "Deepgram HTTP ${resp.code} bytes=${meta.fileSizeBytes} mime=${meta.verifiedMimeType} lang=$langQuery")

            if (!resp.isSuccessful) {
                throw FlowApiException(
                    resp.code,
                    ModelCatalog.mapHttpToErrorCategory(resp.code, raw),
                    ModelCatalog.parseShortError(raw)
                )
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
