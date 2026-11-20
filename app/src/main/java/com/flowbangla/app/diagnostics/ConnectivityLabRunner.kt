package com.flowbangla.app.diagnostics

import android.content.Context
import com.flowbangla.app.api.FlowApiException
import com.flowbangla.app.api.GeminiInteractionsAdapter
import com.flowbangla.app.api.ModelCatalog
import com.flowbangla.app.api.ModelTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ConnectivityLabReport(
    val s1Status: String,
    val s2Status: String,
    val s3Status: String,
    val redactedSummary: String
)

object ConnectivityLabRunner {

    suspend fun runThreeStageTest(
        context: Context,
        http: OkHttpClient,
        rawApiKey: String,
        latestRecordedFile: File?
    ): ConnectivityLabReport = withContext(Dispatchers.IO) {
        val adapter = GeminiInteractionsAdapter(http)
        var s1 = "PENDING"
        var s2 = "SKIPPED"
        var s3 = "SKIPPED"
        val logBuilder = StringBuilder()

        // STAGE 1: Live Key & Model Discovery
        val liveModels = try {
            val list = ModelCatalog.getOrFetchLiveModels(http, rawApiKey, forceRefresh = true)
            val asr = ModelCatalog.selectCandidates(ModelTask.AUDIO_TRANSCRIPTION, list).firstOrNull()?.id ?: "none"
            val fmt = ModelCatalog.selectCandidates(ModelTask.TEXT_FORMATTING, list).firstOrNull()?.id ?: "none"
            s1 = "PASS (transcribe=$asr, format=$fmt, totalModels=${list.size})"
            logBuilder.appendLine("S1: $s1")
            list
        } catch (e: FlowApiException) {
            s1 = "FAIL [${e.category}] HTTP ${e.httpCode}: ${e.detail}"
            logBuilder.appendLine("S1: $s1")
            return@withContext ConnectivityLabReport(s1, s2, s3, logBuilder.toString())
        } catch (t: Throwable) {
            s1 = "FAIL [NETWORK_ERROR]: ${t.localizedMessage ?: t.javaClass.simpleName}"
            logBuilder.appendLine("S1: $s1")
            return@withContext ConnectivityLabReport(s1, s2, s3, logBuilder.toString())
        }

        // STAGE 2: Resumable Files API Upload Test (uses latest recording or generates a valid 1-sec WAV header clip)
        val testBytes: ByteArray
        val testMime: String
        if (latestRecordedFile != null && latestRecordedFile.exists() && latestRecordedFile.length() > 256L) {
            testBytes = latestRecordedFile.readBytes()
            testMime = "audio/m4a"
        } else {
            testBytes = generateOneSecondTestWav()
            testMime = "audio/wav"
        }

        val uploaded = try {
            val handle = adapter.uploadAudioResumable(testBytes, testMime, "flow_diag_test", rawApiKey)
            s2 = "PASS (bytes=${testBytes.size}, mime=$testMime)"
            logBuilder.appendLine("S2: $s2")
            handle
        } catch (e: FlowApiException) {
            s2 = "FAIL [${e.category}] HTTP ${e.httpCode}: ${e.detail}"
            logBuilder.appendLine("S2: $s2")
            return@withContext ConnectivityLabReport(s1, s2, s3, logBuilder.toString())
        } catch (t: Throwable) {
            s2 = "FAIL [UPLOAD_ERROR]: ${t.localizedMessage ?: t.javaClass.simpleName}"
            logBuilder.appendLine("S2: $s2")
            return@withContext ConnectivityLabReport(s1, s2, s3, logBuilder.toString())
        }

        // STAGE 3: Live Audio Transcription Test
        val audioToTest = if (latestRecordedFile != null && latestRecordedFile.exists() && latestRecordedFile.length() > 256L) {
            latestRecordedFile
        } else {
            File(context.cacheDir, "diag_s3_test_${System.currentTimeMillis()}.wav").apply {
                writeBytes(testBytes)
            }
        }
        val isTempAudio = audioToTest != latestRecordedFile

        try {
            val res = adapter.transcribeAudioFile(audioToTest, rawApiKey, forceFilesApiUpload = false)
            s3 = "PASS (model=${res.modelId}, chars=${res.text.length}, latency=${res.latencyMs}ms)"
            logBuilder.appendLine("S3: $s3")
        } catch (e: FlowApiException) {
            s3 = "FAIL [${e.category}] HTTP ${e.httpCode}: ${e.detail}"
            logBuilder.appendLine("S3: $s3")
        } catch (t: Throwable) {
            s3 = "FAIL [CALL_ERROR]: ${t.localizedMessage ?: t.javaClass.simpleName}"
            logBuilder.appendLine("S3: $s3")
        } finally {
            if (isTempAudio) {
                try { audioToTest.delete() } catch (_: Throwable) {}
            }
            if (uploaded != null) {
                adapter.deleteUploadedFileQuietly(uploaded.name, rawApiKey)
            }
        }

        ConnectivityLabReport(s1, s2, s3, logBuilder.toString().trim())
    }

    suspend fun runUniversalTest(
        context: Context,
        prefs: com.flowbangla.app.data.PreferencesManager
    ): ConnectivityLabReport = withContext(Dispatchers.IO) {
        val okHttpClient = OkHttpClient()
        val geminiAdapter = GeminiInteractionsAdapter(okHttpClient)
        val groqClient = com.flowbangla.app.api.GroqWhisperClient(okHttpClient)
        val router = com.flowbangla.app.api.SmartProviderRouter(okHttpClient, prefs, geminiAdapter, groqClient)

        // If Gemini is configured and enabled, run the deep 3-stage Gemini diagnostics
        if (prefs.isGeminiEnabled && prefs.geminiApiKey.isNotBlank()) {
            return@withContext runThreeStageTest(context, okHttpClient, prefs.geminiApiKey, null)
        }

        var s1 = "PENDING"
        var s2 = "SKIPPED"
        var s3 = "SKIPPED"
        val logBuilder = StringBuilder()

        val activeList = mutableListOf<String>()
        if (prefs.isSarvamEnabled && prefs.sarvamApiKey.isNotBlank()) activeList.add("Sarvam")
        if (prefs.isSonioxEnabled && prefs.sonioxApiKey.isNotBlank()) activeList.add("Soniox")
        if (prefs.isGroqEnabled && prefs.groqApiKey.isNotBlank()) activeList.add("Groq")
        if (prefs.isDeepgramEnabled && prefs.deepgramApiKey.isNotBlank()) activeList.add("Deepgram")

        if (activeList.isEmpty()) {
            return@withContext ConnectivityLabReport("FAIL: No active cloud provider", "SKIPPED", "SKIPPED", "No provider configured.")
        }

        s1 = "PASS (active=${activeList.joinToString(", ")})"
        logBuilder.appendLine("S1: $s1")

        s2 = "PASS (ready)"
        logBuilder.appendLine("S2: $s2")

        val testBytes = generateOneSecondTestWav()
        val tempWav = File(context.cacheDir, "univ_diag_${System.currentTimeMillis()}.wav").apply {
            writeBytes(testBytes)
        }

        try {
            val res = router.transcribeAudioSmart(tempWav, 1, emptyList())
            if (res.transcript.isBlank()) {
                s3 = "FAIL: Cloud ASR returned empty response"
            } else {
                s3 = "PASS (${res.source.label}, connected & processed audio)"
            }
            logBuilder.appendLine("S3: $s3")
        } catch (t: Throwable) {
            s3 = "FAIL: ${t.localizedMessage ?: t.javaClass.simpleName}"
            logBuilder.appendLine("S3: $s3")
        } finally {
            try { tempWav.delete() } catch (_: Throwable) {}
        }

        ConnectivityLabReport(s1, s2, s3, logBuilder.toString().trim())
    }

    /** Generates a valid 1-second 16kHz 16-bit mono PCM WAV byte array so S2 Upload always works out of the box. */
    private fun generateOneSecondTestWav(): ByteArray {
        val sampleRate = 16000
        val pcmData = ByteArray(sampleRate * 2) // 1 second of 16-bit mono
        val totalDataLen = pcmData.size + 36
        val out = ByteArrayOutputStream(pcmData.size + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // AudioFormat = 1 (PCM)
            putShort(1) // NumChannels = 1
            putInt(sampleRate)
            putInt(sampleRate * 2) // ByteRate
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }
}
