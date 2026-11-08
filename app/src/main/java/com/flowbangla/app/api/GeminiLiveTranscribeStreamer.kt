package com.flowbangla.app.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams 16kHz 16-bit mono PCM audio chunks over WebSockets to Gemini's Live API
 * (`gemini-3.5-transcribe-live`) WHILE the user is speaking, so transcription is
 * already 95% complete the moment the user taps the Confirm (✓) button!
 */
class GeminiLiveTranscribeStreamer(
    private val http: OkHttpClient,
    private val rawApiKey: String
) {
    companion object {
        private const val TAG = "FlowLiveStreamer"
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isFailed = AtomicBoolean(false)
    private val transcriptBuffer = StringBuilder()
    private val turnCompleteDeferred = CompletableDeferred<String>()

    fun startStreamingSession(customVocabulary: List<String> = emptyList()) {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) {
            isFailed.set(true)
            return
        }

        val request = Request.Builder()
            .url("$WS_BASE_URL?key=$cleanKey")
            .build()

        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live WebSocket opened; sending setup config.")
                val setupPayload = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", "models/gemini-3.5-transcribe-live")
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().put("TEXT"))
                        })
                    })
                }
                webSocket.send(setupPayload.toString())
                isConnected.set(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val serverContent = root.optJSONObject("serverContent") ?: return
                    val modelTurn = serverContent.optJSONObject("modelTurn")
                    if (modelTurn != null) {
                        val parts = modelTurn.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val chunkText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                                if (chunkText.isNotEmpty()) {
                                    synchronized(transcriptBuffer) {
                                        transcriptBuffer.append(chunkText)
                                    }
                                }
                            }
                        }
                    }
                    if (serverContent.optBoolean("turnComplete", false)) {
                        val finalStr = synchronized(transcriptBuffer) { transcriptBuffer.toString().trim() }
                        if (!turnCompleteDeferred.isCompleted) {
                            turnCompleteDeferred.complete(finalStr)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Live WS parse warning: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Live WS failed (${t.message}); Rolling Chunk / File fallback will handle it.")
                isFailed.set(true)
                if (!turnCompleteDeferred.isCompleted) {
                    turnCompleteDeferred.complete("")
                }
            }
        })
    }

    /**
     * Called every ~120ms from the AudioRecord capture loop while the user is speaking.
     */
    fun sendPcmChunk(pcm16kMonoBytes: ByteArray, length: Int) {
        if (isFailed.get() || !isConnected.get()) return
        val ws = webSocket ?: return
        val slice = if (length == pcm16kMonoBytes.size) pcm16kMonoBytes else pcm16kMonoBytes.copyOf(length)
        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)

        // Official BidiGenerateContentRealtimeInput schema (uses `audio`, NOT deprecated `mediaChunks`)
        val frame = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", b64)
                })
            })
        }
        ws.send(frame.toString())
    }

    /**
     * Called the instant the user taps Confirm (✓).
     * Signals audioStreamEnd and waits up to 650ms for the final words.
     */
    suspend fun finishAndAwaitTranscript(timeoutMs: Long = 650L): String? {
        if (isFailed.get() || !isConnected.get()) {
            cancelAndClose()
            return null
        }
        return try {
            val endFrame = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            webSocket?.send(endFrame.toString())

            val completed = withTimeoutOrNull(timeoutMs) {
                turnCompleteDeferred.await()
            }
            val result = if (!completed.isNullOrBlank()) {
                completed
            } else {
                synchronized(transcriptBuffer) { transcriptBuffer.toString().trim() }
            }
            cancelAndClose()
            result.ifEmpty { null }
        } catch (_: Exception) {
            cancelAndClose()
            null
        }
    }

    fun getPartialTranscript(): String = synchronized(transcriptBuffer) { transcriptBuffer.toString().trim() }

    fun cancelAndClose() {
        try {
            webSocket?.close(1000, "Dictation ended or cancelled")
        } catch (_: Exception) {}
        webSocket = null
        isConnected.set(false)
    }
}
