package com.flowbangla.app.api

import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class ModelTask {
    AUDIO_TRANSCRIPTION,
    MULTIMODAL_AUDIO_FALLBACK,
    TEXT_FORMATTING
}

enum class ApiRoute {
    INTERACTIONS,     // POST https://generativelanguage.googleapis.com/v1beta/interactions
    GENERATE_CONTENT  // POST https://generativelanguage.googleapis.com/v1beta/models/{id}:generateContent
}

data class RemoteModel(
    val id: String,
    val methods: Set<String>
)

data class ModelSpec(
    val id: String,
    val task: ModelTask,
    val route: ApiRoute,
    val requiredMethod: String? = null
)

class FlowApiException(
    val httpCode: Int,
    val category: ErrorCategory,
    val detail: String
) : Exception("[$category] HTTP $httpCode: $detail")

enum class ErrorCategory {
    AUDIO_EMPTY_OR_SILENT,
    AUDIO_FILE_INVALID,
    AUDIO_MIME_MISMATCH,
    UPLOAD_FAILED,
    MODEL_NOT_AVAILABLE,
    AUTHENTICATION_FAILED,
    QUOTA_EXCEEDED,
    REQUEST_REJECTED,
    PROVIDER_SERVER_ERROR,
    NETWORK_TIMEOUT,
    PROVIDER_RETURNED_NO_TRANSCRIPT,
    RESPONSE_PARSE_FAILED
}

object ModelCatalog {

    // Verified Active Gemini 3.x Catalogue.
    // STRICT RULE: NEVER add gemini-1.5-*, gemini-2.0-*, or gemini-2.5-* here.
    val specs: List<ModelSpec> = listOf(
        // 1. Dedicated Speech-to-Text (Primary Cloud ASR)
        ModelSpec(
            id = "gemini-3.5-transcribe",
            task = ModelTask.AUDIO_TRANSCRIPTION,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),

        // 2. Multimodal Audio Fallback (if gemini-3.5-transcribe is unavailable for a key)
        ModelSpec(
            id = "gemini-3.8-flash",
            task = ModelTask.MULTIMODAL_AUDIO_FALLBACK,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),
        ModelSpec(
            id = "gemini-3.5-flash-lite",
            task = ModelTask.MULTIMODAL_AUDIO_FALLBACK,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),
        ModelSpec(
            id = "gemini-3.6-flash",
            task = ModelTask.MULTIMODAL_AUDIO_FALLBACK,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),

        // 3. Fast Text Formatting & Document Classification
        ModelSpec(
            id = "gemini-3.5-flash-lite",
            task = ModelTask.TEXT_FORMATTING,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),
        ModelSpec(
            id = "gemini-3.8-flash",
            task = ModelTask.TEXT_FORMATTING,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),
        ModelSpec(
            id = "gemini-3.6-flash",
            task = ModelTask.TEXT_FORMATTING,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        ),
        ModelSpec(
            id = "gemini-3.1-flash-lite",
            task = ModelTask.TEXT_FORMATTING,
            route = ApiRoute.INTERACTIONS,
            requiredMethod = null
        )
    )

    @Volatile
    private var cachedLiveModels: List<RemoteModel> = emptyList()
    @Volatile
    private var cacheTimestampMs: Long = 0L
    private val disabledForSession = mutableSetOf<String>()

    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    fun normalizeId(rawName: String): String = rawName.trim().removePrefix("models/")

    fun markModelUnavailableForSession(modelId: String) {
        disabledForSession.add(normalizeId(modelId))
    }

    fun invalidateCache() {
        cachedLiveModels = emptyList()
        cacheTimestampMs = 0L
        disabledForSession.clear()
    }

    fun getCachedLiveModels(): List<RemoteModel> = cachedLiveModels

    fun triggerBackgroundModelFetch(http: OkHttpClient, rawApiKey: String) {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) return
        val now = System.currentTimeMillis()
        if (cachedLiveModels.isNotEmpty() && (now - cacheTimestampMs) < CACHE_TTL_MS) return

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                getOrFetchLiveModels(http, cleanKey, forceRefresh = false)
            } catch (e: Exception) {
                android.util.Log.w("ModelCatalog", "Background live model fetch warning: ${e.message}")
            }
        }
    }

    fun getOrFetchLiveModels(http: OkHttpClient, rawApiKey: String, forceRefresh: Boolean = false): List<RemoteModel> {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) {
            throw FlowApiException(400, ErrorCategory.AUTHENTICATION_FAILED, "Gemini API key is empty.")
        }

        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedLiveModels.isNotEmpty() && (now - cacheTimestampMs) < CACHE_TTL_MS) {
            return cachedLiveModels
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", cleanKey)
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val category = mapHttpToErrorCategory(response.code, body)
                    throw FlowApiException(response.code, category, parseShortError(body))
                }

                val modelsArray = JSONObject(body).optJSONArray("models") ?: return emptyList()
                val parsed = (0 until modelsArray.length()).mapNotNull { idx ->
                    val obj = modelsArray.optJSONObject(idx) ?: return@mapNotNull null
                    val name = obj.optString("name", "")
                    if (name.isBlank()) return@mapNotNull null
                    val methodsJson = obj.optJSONArray("supportedGenerationMethods")
                    val methods = if (methodsJson == null) emptySet() else {
                        (0 until methodsJson.length()).mapNotNull { mIdx -> methodsJson.optString(mIdx) }.toSet()
                    }
                    RemoteModel(id = normalizeId(name), methods = methods)
                }

                cachedLiveModels = parsed
                cacheTimestampMs = now
                return parsed
            }
        } catch (e: FlowApiException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ModelCatalog", "getOrFetchLiveModels network exception: ${e.message}")
            return cachedLiveModels
        }
    }

    fun selectCandidates(task: ModelTask, liveModels: List<RemoteModel>): List<ModelSpec> {
        if (liveModels.isEmpty()) {
            return specs.filter { spec ->
                spec.task == task && spec.id !in disabledForSession
            }
        }
        val liveIds = liveModels.associateBy { it.id }
        val matched = specs.filter { spec ->
            spec.task == task &&
                spec.id !in disabledForSession &&
                liveIds.containsKey(spec.id) &&
                (spec.requiredMethod == null || liveIds[spec.id]?.methods?.contains(spec.requiredMethod) == true)
        }
        return if (matched.isNotEmpty()) matched else {
            specs.filter { spec -> spec.task == task && spec.id !in disabledForSession }
        }
    }

    fun mapHttpToErrorCategory(httpCode: Int, body: String): ErrorCategory {
        val lower = body.lowercase()
        return when (httpCode) {
            400 -> if ("api key not valid" in lower || "api_key_invalid" in lower) {
                ErrorCategory.AUTHENTICATION_FAILED
            } else {
                ErrorCategory.REQUEST_REJECTED
            }
            401, 403 -> ErrorCategory.AUTHENTICATION_FAILED
            404 -> ErrorCategory.MODEL_NOT_AVAILABLE
            429 -> ErrorCategory.QUOTA_EXCEEDED
            in 500..599 -> ErrorCategory.PROVIDER_SERVER_ERROR
            else -> ErrorCategory.REQUEST_REJECTED
        }
    }

    fun parseShortError(rawBody: String): String {
        return try {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")?.take(160)
                ?: rawBody.take(160)
        } catch (_: Exception) {
            rawBody.take(160)
        }
    }
}
