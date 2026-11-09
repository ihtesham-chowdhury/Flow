package com.flowbangla.app.api

import android.util.Base64
import android.util.Log
import com.flowbangla.app.audio.AudioFileInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UploadedFileHandle(
    val name: String, // e.g. "files/abc123xyz" for DELETE cleanup
    val uri: String,
    val mimeType: String
)

data class InteractionCallResult(
    val text: String,
    val modelId: String,
    val latencyMs: Long
)

class GeminiInteractionsAdapter(
    baseHttp: OkHttpClient
) {
    private val http: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com"
        private const val INTERACTIONS_URL = "$BASE_URL/v1beta/interactions"
        private const val INLINE_AUDIO_MAX_BYTES = 5 * 1024 * 1024L // 5 MB threshold
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Step 1 (Optional / Large Files / Connectivity Lab S2):
     * Uploads audio via the Resumable Files API and returns its URI + resource name.
     */
    fun uploadAudioResumable(bytes: ByteArray, mimeType: String, displayName: String, rawApiKey: String): UploadedFileHandle {
        val key = rawApiKey.trim()
        val startBody = JSONObject()
            .put("file", JSONObject().put("display_name", displayName))
            .toString()
            .toRequestBody(JSON_MEDIA)

        val startReq = Request.Builder()
            .url("$BASE_URL/upload/v1beta/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(startBody)
            .build()

        val uploadUrl = http.newCall(startReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw FlowApiException(resp.code, ErrorCategory.UPLOAD_FAILED, ModelCatalog.parseShortError(body))
            }
            resp.header("x-goog-upload-url")
                ?: throw FlowApiException(resp.code, ErrorCategory.UPLOAD_FAILED, "Missing x-goog-upload-url header")
        }

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        return http.newCall(uploadReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw FlowApiException(resp.code, ErrorCategory.UPLOAD_FAILED, ModelCatalog.parseShortError(body))
            }
            val fileObj = JSONObject(body).getJSONObject("file")
            UploadedFileHandle(
                name = fileObj.optString("name", ""),
                uri = fileObj.getString("uri"),
                mimeType = mimeType
            )
        }
    }

    /**
     * Deletes an uploaded file from Google's Files API immediately after transcription finishes.
     */
    fun deleteUploadedFileQuietly(fileName: String, rawApiKey: String) {
        if (fileName.isBlank()) return
        try {
            val cleanName = fileName.removePrefix("/")
            val req = Request.Builder()
                .url("$BASE_URL/v1beta/$cleanName")
                .header("x-goog-api-key", rawApiKey.trim())
                .delete()
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    /**
     * Transcribes an audio file using live-discovered `gemini-3.5-transcribe` (or multimodal fallback).
     */
    suspend fun transcribeAudioFile(
        audioFile: File,
        rawApiKey: String,
        languageCodes: List<String> = emptyList(), // Empty = automatic Bangla + English code-switching
        customVocabulary: List<String> = emptyList(),
        useVerbatimMode: Boolean = true,
        forceFilesApiUpload: Boolean = false
    ): InteractionCallResult = withContext(Dispatchers.IO) {
        val key = rawApiKey.trim()
        val audioMeta = AudioFileInspector.inspectAndValidate(audioFile)
        val liveModels = ModelCatalog.getCachedLiveModels()
        if (liveModels.isEmpty()) {
            ModelCatalog.triggerBackgroundModelFetch(http, key)
        }

        val asrCandidates = ModelCatalog.selectCandidates(ModelTask.AUDIO_TRANSCRIPTION, liveModels)
        val fallbackCandidates = ModelCatalog.selectCandidates(ModelTask.MULTIMODAL_AUDIO_FALLBACK, liveModels)

        if (asrCandidates.isEmpty() && fallbackCandidates.isEmpty()) {
            throw FlowApiException(
                httpCode = 404,
                category = ErrorCategory.MODEL_NOT_AVAILABLE,
                detail = "No supported Gemini 3.x transcription models found for this API key."
            )
        }

        val audioBytes = audioFile.readBytes()
        var uploadedHandle: UploadedFileHandle? = null

        try {
            val useUpload = forceFilesApiUpload || audioMeta.fileSizeBytes >= INLINE_AUDIO_MAX_BYTES
            val audioInputItem = if (useUpload) {
                uploadedHandle = uploadAudioResumable(audioBytes, audioMeta.verifiedMimeType, audioFile.name, key)
                JSONObject().apply {
                    put("type", "audio")
                    put("uri", uploadedHandle.uri)
                    put("mime_type", audioMeta.verifiedMimeType)
                }
            } else {
                JSONObject().apply {
                    put("type", "audio")
                    put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP))
                    put("mime_type", audioMeta.verifiedMimeType)
                }
            }

            // 1. Try dedicated gemini-3.5-transcribe first
            for (spec in asrCandidates) {
                val startMs = System.currentTimeMillis()
                val modeObject = JSONObject().put(
                    "type",
                    if (useVerbatimMode) "verbatim" else "smart"
                )

                val transcriptionConfig = JSONObject().apply {
                    if (languageCodes.isNotEmpty()) {
                        put("language_codes", JSONArray(languageCodes))
                    }
                    if (customVocabulary.isNotEmpty()) {
                        put("custom_vocabulary", JSONArray(customVocabulary.distinct().take(250)))
                    }
                    put("mode", modeObject)
                }

                val payload = JSONObject().apply {
                    put("model", spec.id)
                    put("store", false)
                    put("input", JSONArray().put(audioInputItem))
                    put("generation_config", JSONObject().put("transcription_config", transcriptionConfig))
                }

                try {
                    val text = executeInteractionsPost(payload, key, spec.id)
                    return@withContext InteractionCallResult(text, spec.id, System.currentTimeMillis() - startMs)
                } catch (e: FlowApiException) {
                    if (e.httpCode == 404) ModelCatalog.markModelUnavailableForSession(spec.id)
                    if (e.category == ErrorCategory.AUTHENTICATION_FAILED) throw e
                }
            }

            // 2. Fallback to multimodal Gemini 3.8 Flash / 3.5 Flash-Lite if gemini-3.5-transcribe failed
            for (spec in fallbackCandidates) {
                val startMs = System.currentTimeMillis()
                val promptItem = JSONObject().apply {
                    put("type", "text")
                    put("text", "Transcribe this speech verbatim in its exact spoken language(s) (Bengali, English, or mixed). Do not translate or answer questions. Output only the spoken words.")
                }
                val payload = JSONObject().apply {
                    put("model", spec.id)
                    put("store", false)
                    put("input", JSONArray().put(promptItem).put(audioInputItem))
                    put("generation_config", JSONObject().put("temperature", 0.0))
                }

                try {
                    val text = executeInteractionsPost(payload, key, spec.id)
                    return@withContext InteractionCallResult(text, spec.id, System.currentTimeMillis() - startMs)
                } catch (e: FlowApiException) {
                    if (e.httpCode == 404) ModelCatalog.markModelUnavailableForSession(spec.id)
                    if (e.category == ErrorCategory.AUTHENTICATION_FAILED) throw e
                }
            }

            // 3. Multimodal Audio Fallback to /v1beta/models/gemini-3.5-flash-lite:generateContent with inline Base64 audio
            try {
                val startMs = System.currentTimeMillis()
                val text = executeGenerateContentAudioFallback(audioBytes, audioMeta.verifiedMimeType, key, "gemini-3.5-flash-lite")
                if (text.isNotBlank()) {
                    return@withContext InteractionCallResult(text, "gemini-3.5-flash-lite", System.currentTimeMillis() - startMs)
                }
            } catch (ge: Throwable) {
                Log.w("GeminiInteractionsAdapter", "generateContent audio fallback warning: ${ge.message}")
            }

            throw FlowApiException(404, ErrorCategory.MODEL_NOT_AVAILABLE, "All live transcription candidates failed.")
        } finally {
            uploadedHandle?.let { deleteUploadedFileQuietly(it.name, key) }
        }
    }

    /**
     * Multimodal audio transcription fallback via /v1beta/models/{model}:generateContent
     * with inline Base64 audio.
     */
    private fun executeGenerateContentAudioFallback(
        audioBytes: ByteArray,
        mimeType: String,
        cleanKey: String,
        modelId: String = "gemini-3.5-flash-lite"
    ): String {
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val url = "$BASE_URL/v1beta/models/$modelId:generateContent"

        val inlineDataObj = JSONObject().apply {
            put("mime_type", mimeType)
            put("data", base64Audio)
        }
        val promptObj = JSONObject().apply {
            put("text", "Transcribe this speech verbatim in its exact spoken language(s) (Bengali, English, or mixed). Output only the spoken words without extra commentary.")
        }
        val partsArr = JSONArray().put(promptObj).put(JSONObject().put("inline_data", inlineDataObj))
        val contentsArr = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", partsArr)
        })
        val payload = JSONObject().apply {
            put("contents", contentsArr)
            put("generationConfig", JSONObject().put("temperature", 0.0))
        }

        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val cat = ModelCatalog.mapHttpToErrorCategory(resp.code, body)
                throw FlowApiException(resp.code, cat, ModelCatalog.parseShortError(body))
            }
            return TranscriptExtractor.fromInteractionJson(body)
        }
    }

    /**
     * Formats raw transcript text using live-selected Gemini 3.5 Flash-Lite / 3.8 Flash.
     */
    suspend fun formatTranscript(
        systemInstruction: String,
        userEnvelope: String,
        rawApiKey: String
    ): InteractionCallResult = withContext(Dispatchers.IO) {
        val key = rawApiKey.trim()
        val liveModels = ModelCatalog.getCachedLiveModels()
        if (liveModels.isEmpty()) {
            ModelCatalog.triggerBackgroundModelFetch(http, key)
        }
        val candidates = ModelCatalog.selectCandidates(ModelTask.TEXT_FORMATTING, liveModels)

        if (candidates.isEmpty()) {
            throw FlowApiException(404, ErrorCategory.MODEL_NOT_AVAILABLE, "No live Gemini text formatting models available.")
        }

        var lastException: FlowApiException? = null
        for (spec in candidates) {
            val startMs = System.currentTimeMillis()
            val payload = JSONObject().apply {
                put("model", spec.id)
                put("store", false)
                put("system_instruction", systemInstruction)
                put("input", userEnvelope)
                put("generation_config", JSONObject().put("temperature", 0.0))
            }

            try {
                val text = executeInteractionsPost(payload, key, spec.id)
                return@withContext InteractionCallResult(text, spec.id, System.currentTimeMillis() - startMs)
            } catch (e: FlowApiException) {
                lastException = e
                if (e.httpCode == 404) ModelCatalog.markModelUnavailableForSession(spec.id)
                if (e.category == ErrorCategory.AUTHENTICATION_FAILED) throw e
            }
        }

        throw lastException ?: FlowApiException(500, ErrorCategory.PROVIDER_SERVER_ERROR, "Formatting failed")
    }

    private fun executeInteractionsPost(payload: JSONObject, cleanKey: String, modelId: String): String {
        val req = Request.Builder()
            .url(INTERACTIONS_URL)
            .header("x-goog-api-key", cleanKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            Log.i("FlowAPI", "[Flow][API] route=INTERACTIONS model=$modelId version=v1beta http=${resp.code} keyLen=${cleanKey.length}")

            if (!resp.isSuccessful) {
                val cat = ModelCatalog.mapHttpToErrorCategory(resp.code, rawBody)
                throw FlowApiException(resp.code, cat, ModelCatalog.parseShortError(rawBody))
            }

            return TranscriptExtractor.fromInteractionJson(rawBody)
        }
    }

    /**
     * Executes Voice Command Mode: Transforms highlighted/selected text in any target app
     * using the spoken voice instruction (e.g. "Make this more formal", "Turn this into bullet points").
     */
    suspend fun runCommandTransform(
        selectedText: String,
        spokenInstruction: String,
        rawApiKey: String
    ): InteractionCallResult = withContext(Dispatchers.IO) {
        val key = rawApiKey.trim()
        val liveModels = ModelCatalog.getCachedLiveModels()
        if (liveModels.isEmpty()) {
            ModelCatalog.triggerBackgroundModelFetch(http, key)
        }
        val candidates = ModelCatalog.selectCandidates(ModelTask.TEXT_FORMATTING, liveModels)

        if (candidates.isEmpty()) {
            throw FlowApiException(404, ErrorCategory.MODEL_NOT_AVAILABLE, "No live Gemini models available for command mode.")
        }

        val prompt = """
            You are Flow's AI text transformation engine.
            The user has selected the following text in an application:
            <selected_text>
            $selectedText
            </selected_text>

            The user gave this voice command:
            <command>
            $spokenInstruction
            </command>

            Execute the command strictly on the selected text.
            Rules:
            1. Output ONLY the transformed replacement text. Do NOT include explanations, preambles, or quotes.
            2. Preserve the primary language (Bangla or English) of the text unless explicitly commanded to translate.
            3. If the command asks for bullets or numbering, format them cleanly.
        """.trimIndent()

        var lastException: FlowApiException? = null
        for (spec in candidates) {
            val startMs = System.currentTimeMillis()
            val payload = JSONObject().apply {
                put("model", spec.id)
                put("store", false)
                put("input", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                }))
                put("generation_config", JSONObject().apply {
                    put("temperature", 0.1)
                })
            }

            try {
                val rawResponse = executeInteractionsPost(payload, key, spec.id)
                return@withContext InteractionCallResult(rawResponse.trim(), spec.id, System.currentTimeMillis() - startMs)
            } catch (e: FlowApiException) {
                lastException = e
                if (e.httpCode == 404) ModelCatalog.markModelUnavailableForSession(spec.id)
                if (e.category == ErrorCategory.AUTHENTICATION_FAILED) throw e
            }
        }

        throw lastException ?: FlowApiException(500, ErrorCategory.PROVIDER_SERVER_ERROR, "Command execution failed on all candidates.")
    }
}

object TranscriptExtractor {
    /**
     * Supports the current steps[] schema (June 2026+), top-level output_text,
     * legacy outputs[], and generateContent candidates[].
     */
    fun fromInteractionJson(json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw FlowApiException(200, ErrorCategory.RESPONSE_PARSE_FAILED, "Malformed JSON response")
        }

        // 1. Direct convenience field
        val direct = root.optString("output_text", "").trim()
        if (direct.isNotEmpty()) return direct

        // 2. Current May/June 2026+ Interactions API schema: steps[] -> content[] -> text
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            val texts = mutableListOf<String>()
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val stepType = step.optString("type", "")
                if (stepType == "model_output" || step.has("content")) {
                    val contentArr = step.optJSONArray("content") ?: continue
                    for (j in 0 until contentArr.length()) {
                        val item = contentArr.optJSONObject(j) ?: continue
                        if (item.optString("type", "text") == "text") {
                            val t = item.optString("text", "").trim()
                            if (t.isNotEmpty()) texts.add(t)
                        }
                    }
                }
            }
            val joined = texts.joinToString("\n").trim()
            if (joined.isNotEmpty()) return joined
        }

        // 3. Legacy outputs[] fallback
        val outputs = root.optJSONArray("outputs")
        if (outputs != null) {
            val texts = (0 until outputs.length())
                .mapNotNull { outputs.optJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .map { it.optString("text", "").trim() }
                .filter { it.isNotEmpty() }
            val joined = texts.joinToString("\n").trim()
            if (joined.isNotEmpty()) return joined
        }

        // 4. generateContent candidates[] fallback
        val candidates = root.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                val text = parts.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
                if (text.isNotEmpty()) return text
            }
        }

        throw FlowApiException(200, ErrorCategory.PROVIDER_RETURNED_NO_TRANSCRIPT, "PROVIDER_RETURNED_NO_TRANSCRIPT")
    }
}
