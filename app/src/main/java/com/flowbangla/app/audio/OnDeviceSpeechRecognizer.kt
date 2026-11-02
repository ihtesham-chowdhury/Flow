package com.flowbangla.app.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.flowbangla.app.model.SttLanguage

class OnDeviceSpeechRecognizer(
    private val context: Context,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript: (String) -> Unit,
    private val onErrorOccurred: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isUserRecording = false
    private val accumulatedSegments = StringBuilder()
    private var latestPartialSegment = ""
    private var currentLanguage: SttLanguage = SttLanguage.BENGALI
    private var forceOfflineOnly: Boolean = false
    private var consecutiveErrorCount = 0

    companion object {
        private const val TAG = "OnDeviceSpeechRecognizer"
        private const val ERROR_SERVER_DISCONNECTED_COMPAT = 11
    }

    fun startListening(language: SttLanguage, preferOfflineOnly: Boolean) {
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
            isUserRecording = true
            accumulatedSegments.clear()
            latestPartialSegment = ""
            currentLanguage = language
            forceOfflineOnly = preferOfflineOnly
            consecutiveErrorCount = 0
            initAndStartSegment()
        }
    }

    private fun initAndStartSegment() {
        if (!isUserRecording) return

        if (speechRecognizer == null) {
            val recognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "createOnDeviceSpeechRecognizer failed, fallback to default: ${e.message}")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer.setRecognitionListener(createRecognitionListener())
            speechRecognizer = recognizer
        }

        try {
            speechRecognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening threw exception, recreating recognizer: ${e.message}")
            recreateAndStart(250L)
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage.localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Silence detection thresholds to give user natural pausing room without abrupt cutoffs
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                consecutiveErrorCount = 0
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onAmplitudeChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotEmpty()) {
                    latestPartialSegment = partial
                    val combined = buildCombinedTranscript(partial)
                    onPartialTranscript(combined)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val segment = matches?.firstOrNull()?.trim().orEmpty()
                    .ifEmpty { latestPartialSegment }

                if (segment.isNotEmpty()) {
                    if (accumulatedSegments.isNotEmpty()) accumulatedSegments.append(" ")
                    accumulatedSegments.append(segment)
                }
                latestPartialSegment = ""
                consecutiveErrorCount = 0

                if (isUserRecording) {
                    // User hasn't tapped Stop yet; seamlessly continue listening for next phrase
                    restartListeningWithDelay(150L)
                } else {
                    finishAndDeliver()
                }
            }

            override fun onError(error: Int) {
                Log.w(TAG, "onError: code $error, isUserRecording=$isUserRecording, accumulated='$accumulatedSegments', partial='$latestPartialSegment'")

                if (isUserRecording) {
                    consecutiveErrorCount++

                    val isRecoverable = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == ERROR_SERVER_DISCONNECTED_COMPAT ||
                            error == SpeechRecognizer.ERROR_CLIENT

                    if (isRecoverable && consecutiveErrorCount <= 4) {
                        if (error == ERROR_SERVER_DISCONNECTED_COMPAT || error == SpeechRecognizer.ERROR_CLIENT) {
                            // Service disconnect or client desync requires clean recreation with backoff
                            recreateAndStart(300L)
                        } else {
                            // Brief pause / silence timeout can restart quickly
                            restartListeningWithDelay(200L)
                        }
                        return
                    }

                    // Exceeded retries or non-recoverable: NEVER throw away transcribed words!
                    if (accumulatedSegments.isNotEmpty() || latestPartialSegment.isNotEmpty()) {
                        Log.i(TAG, "Delivering accumulated segments despite error $error")
                        finishAndDeliver()
                        return
                    }

                    isUserRecording = false
                    destroyRecognizerInternal()
                    onErrorOccurred(mapErrorCodeToMessage(error, forceOfflineOnly))
                } else {
                    if (accumulatedSegments.isNotEmpty() || latestPartialSegment.isNotEmpty()) {
                        finishAndDeliver()
                    } else {
                        destroyRecognizerInternal()
                        onErrorOccurred(mapErrorCodeToMessage(error, forceOfflineOnly))
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun restartListeningWithDelay(delayMs: Long) {
        if (!isUserRecording) return
        mainHandler.postDelayed({
            if (!isUserRecording) return@postDelayed
            try {
                speechRecognizer?.startListening(buildIntent())
            } catch (e: Exception) {
                Log.w(TAG, "startListening failed on existing recognizer, recreating: ${e.message}")
                recreateAndStart(200L)
            }
        }, delayMs)
    }

    private fun recreateAndStart(delayMs: Long) {
        if (!isUserRecording) return
        destroyRecognizerInternal()
        mainHandler.postDelayed({
            if (isUserRecording) {
                initAndStartSegment()
            }
        }, delayMs)
    }

    fun stopListening() {
        mainHandler.post {
            if (!isUserRecording) return@post
            isUserRecording = false
            mainHandler.removeCallbacksAndMessages(null)
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
                finishAndDeliver()
            }
            // Safety timeout: if onResults doesn't fire within 500ms after stopListening, flush immediately
            mainHandler.postDelayed({
                if (speechRecognizer != null) {
                    finishAndDeliver()
                }
            }, 500L)
        }
    }

    private fun finishAndDeliver() {
        mainHandler.removeCallbacksAndMessages(null)
        if (latestPartialSegment.isNotEmpty()) {
            if (accumulatedSegments.isNotEmpty()) accumulatedSegments.append(" ")
            accumulatedSegments.append(latestPartialSegment)
            latestPartialSegment = ""
        }
        destroyRecognizerInternal()
        val finalResult = accumulatedSegments.toString().trim()
        if (finalResult.isNotEmpty()) {
            onFinalTranscript(finalResult)
        } else {
            onErrorOccurred("No speech detected / কোনো কথা শোনা যায়নি")
        }
    }

    private fun buildCombinedTranscript(activePartial: String): String {
        return if (accumulatedSegments.isEmpty()) {
            activePartial
        } else {
            "${accumulatedSegments} $activePartial".trim()
        }
    }

    private fun destroyRecognizerInternal() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    fun release() {
        mainHandler.post {
            isUserRecording = false
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizerInternal()
        }
    }

    private fun mapErrorCodeToMessage(error: Int, offlineRequired: Boolean): String {
        return when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required / মাইক্রোফোন পারমিশন প্রয়োজন।"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                if (offlineRequired) "Offline language pack missing in Google Speech Services."
                else "Network connection error during speech recognition."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "Language (${currentLanguage.localeTag}) pack not downloaded in Google Voice Typing."
            ERROR_SERVER_DISCONNECTED_COMPAT ->
                "Speech recognition disconnected. Please verify Google Voice Typing offline pack is downloaded."
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Please check microphone."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Please try again."
            else -> "Speech recognition error (code $error)."
        }
    }
}
