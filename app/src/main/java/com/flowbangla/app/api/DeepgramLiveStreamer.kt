package com.flowbangla.app.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * True zero-lag (<150ms) real-time speech-to-text WebSocket streamer powered by Deepgram Nova-3.
 * Streams 16kHz 16-bit mono PCM binary frames while AudioRecorder simultaneously saves the
 * full lossless WAV file for final post-processing and Voice Vault backup!
 */
class DeepgramLiveStreamer(
    private val http: OkHttpClient,
    private val rawApiKey: String,
    private val onLiveTextUpdated: (String) -> Unit
) {
    companion object {
        private const val TAG = "FlowDeepgramLive"
    }

    private var webSocket: WebSocket? = null
    private val isOpen = AtomicBoolean(false)
    private val finalizedSegments = StringBuilder()

    fun start(lang: SpeechLang) {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) return

        val langParam = when (lang) {
            SpeechLang.ENGLISH -> "en"
            SpeechLang.BANGLA -> "bn"
            SpeechLang.MIXED -> "multi"
        }

        val url = "wss://api.deepgram.com/v1/listen" +
            "?model=nova-3&encoding=linear16&sample_rate=16000&channels=1" +
            "&interim_results=true&smart_format=true&punctuate=true&language=$langParam"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Token $cleanKey")
            .build()

        synchronized(finalizedSegments) { finalizedSegments.clear() }

        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen.set(true)
                Log.i(TAG, "Deepgram Live WebSocket opened ($langParam)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val channel = root.optJSONObject("channel") ?: return
                    val alt = channel.optJSONArray("alternatives")?.optJSONObject(0) ?: return
                    val transcript = alt.optString("transcript", "").trim()
                    if (transcript.isEmpty()) return

                    val isFinal = root.optBoolean("is_final", false)
                    val display = synchronized(finalizedSegments) {
                        if (isFinal) {
                            if (finalizedSegments.isNotEmpty()) finalizedSegments.append(" ")
                            finalizedSegments.append(transcript)
                            finalizedSegments.toString()
                        } else {
                            if (finalizedSegments.isNotEmpty()) {
                                "${finalizedSegments} $transcript"
                            } else {
                                transcript
                            }
                        }
                    }
                    onLiveTextUpdated(display)
                } catch (_: Throwable) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen.set(false)
                Log.w(TAG, "Deepgram Live WS error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen.set(false)
            }
        })
    }

    /**
     * Sends raw 16kHz 16-bit mono PCM bytes directly from AudioRecorder's read loop!
     */
    fun sendPcmFrame(pcmBytes: ByteArray, length: Int) {
        if (!isOpen.get()) return
        try {
            webSocket?.send(pcmBytes.toByteString(0, length))
        } catch (_: Throwable) {}
    }

    fun close() {
        isOpen.set(false)
        try {
            webSocket?.send("""{"type": "CloseStream"}""")
            webSocket?.close(1000, "Recording finished")
        } catch (_: Throwable) {}
        webSocket = null
    }
}
