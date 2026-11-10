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

        // STAGE 3: Live Interactions API Call + Immediate File Cleanup
        try {
            if (latestRecordedFile != null && latestRecordedFile.exists() && latestRecordedFile.length() > 256L) {
                val res = adapter.transcribeAudioFile(latestRecordedFile, rawApiKey, forceFilesApiUpload = false)
                s3 = "PASS (model=${res.modelId}, chars=${res.text.length}, latency=${res.latencyMs}ms)"
            } else {
                // Verify Interactions API text/formatting route if no voice recording exists yet
                val fmtRes = adapter.formatTranscript(
                    systemInstruction = "Output the exact input text.",
                    userEnvelope = "আমি আগামীকাল মিটিংয়ে থাকব।",
                    rawApiKey = rawApiKey
                )
                s3 = "PASS (model=${fmtRes.modelId}, chars=${fmtRes.text.length}, latency=${fmtRes.latencyMs}ms)"
            }
            logBuilder.appendLine("S3: $s3")
        } catch (e: FlowApiException) {
            s3 = "FAIL [${e.category}] HTTP ${e.httpCode}: ${e.detail}"
            logBuilder.appendLine("S3: $s3")
        } catch (t: Throwable) {
            s3 = "FAIL [CALL_ERROR]: ${t.localizedMessage ?: t.javaClass.simpleName}"
            logBuilder.appendLine("S3: $s3")
        } finally {
            if (uploaded != null) {
                adapter.deleteUploadedFileQuietly(uploaded.name, rawApiKey)
            }
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
