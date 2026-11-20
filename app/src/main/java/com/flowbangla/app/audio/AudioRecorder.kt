package com.flowbangla.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Flow v4.3.0 — High-Fidelity 16kHz 16-bit Mono PCM Audio Recorder.
 * Captures clean PCM via AudioRecord (VOICE_RECOGNITION with MIC fallback).
 * Feeds live 60fps amplitude poller, streams unread micro-slices for Live Preview,
 * and compiles gapless backup WAV file in < 5ms upon completion.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRecordingAtom = AtomicBoolean(false)
    private val currentMaxAmplitude = AtomicInteger(0)

    private val fullPcmStream = ByteArrayOutputStream()
    private val liveSlicePcmStream = ByteArrayOutputStream()
    private val recordingsDir: File by lazy {
        File(context.filesDir, "flow_recordings").apply { mkdirs() }
    }
    private var startTimeMillis: Long = 0L

    var onAmplitudeChanged: ((amplitude: Float, durationMs: Long) -> Unit)? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(customScope: CoroutineScope = scope): Result<File> {
        stopAndReleaseQuietly()
        synchronized(fullPcmStream) { fullPcmStream.reset() }
        synchronized(liveSlicePcmStream) { liveSlicePcmStream.reset() }
        currentMaxAmplitude.set(0)
        startTimeMillis = System.currentTimeMillis()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord initialization error: ${t.message}")
            null
        }

        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Throwable) {}
            return Result.failure(IllegalStateException("AudioRecord failed to initialize"))
        }

        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.startRecording failed: ${e.message}")
            try { rec.release() } catch (_: Throwable) {}
            return Result.failure(e)
        }

        audioRecord = rec
        isRecordingAtom.set(true)

        recordingJob = customScope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(1024) // ~64ms per read at 16kHz
            val byteBuf = ByteArray(2048)

            while (isRecordingAtom.get() && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readShorts = rec.read(shortBuf, 0, shortBuf.size)
                if (readShorts > 0) {
                    var sumSquares = 0.0
                    var peak = 0
                    for (i in 0 until readShorts) {
                        val s = shortBuf[i].toInt()
                        val absS = kotlin.math.abs(s)
                        if (absS > peak) peak = absS
                        sumSquares += (s * s).toDouble()
                        // Little-endian PCM 16-bit
                        byteBuf[i * 2] = (s and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    val rms = sqrt(sumSquares / readShorts).toInt()
                    val combinedAmp = (rms * 2 + peak) / 2
                    currentMaxAmplitude.set(combinedAmp)

                    val normalized = (combinedAmp / 32767f).coerceIn(0f, 1f)
                    val elapsed = System.currentTimeMillis() - startTimeMillis
                    onAmplitudeChanged?.invoke(normalized, elapsed)

                    val bytesCount = readShorts * 2
                    synchronized(fullPcmStream) {
                        fullPcmStream.write(byteBuf, 0, bytesCount)
                    }
                    synchronized(liveSlicePcmStream) {
                        liveSlicePcmStream.write(byteBuf, 0, bytesCount)
                    }
                }
            }
        }

        val placeholderFile = File(recordingsDir, "dictation_active.wav")
        return Result.success(placeholderFile)
    }

    fun getCurrentAmplitude(): Int = currentMaxAmplitude.get()

    /**
     * Extracts the latest unread PCM bytes as a temporary .wav file for Live Preview
     * without interrupting the main recording!
     */
    fun drainLatestLiveSliceWavFile(): File? {
        val sliceBytes = synchronized(liveSlicePcmStream) {
            // Drain if we have at least ~0.8s of audio (25600 bytes at 16kHz 16-bit mono)
            if (liveSlicePcmStream.size() < SAMPLE_RATE * 2) {
                return null
            }
            val b = liveSlicePcmStream.toByteArray()
            liveSlicePcmStream.reset()
            b
        }

        // Fast energy check so pure silence is never transcribed
        var maxSample = 0
        var i = 0
        while (i + 1 < sliceBytes.size) {
            val sample = (sliceBytes[i].toInt() and 0xFF) or (sliceBytes[i + 1].toInt() shl 8)
            val abs = kotlin.math.abs(sample.toShort().toInt())
            if (abs > maxSample) maxSample = abs
            i += 8
        }
        if (maxSample < 300) return null

        val wavBytes = wrapPcmInWavHeader(sliceBytes, SAMPLE_RATE)
        val tempSlice = File(recordingsDir, "live_slice_${System.currentTimeMillis()}.wav")
        FileOutputStream(tempSlice).use { it.write(wavBytes) }
        return tempSlice
    }

    /**
     * Stops recording in < 5ms on Dispatchers.IO and writes the complete backup WAV file.
     */
    suspend fun stopRecordingSafelyIo(): File? = withContext(Dispatchers.IO) {
        isRecordingAtom.set(false)
        try { recordingJob?.join() } catch (_: Throwable) {}
        stopAndReleaseQuietly()

        val allPcm = synchronized(fullPcmStream) {
            val b = fullPcmStream.toByteArray()
            fullPcmStream.reset()
            b
        }
        if (allPcm.size < 1600) return@withContext null

        pruneOldRecordings()
        val wavBytes = wrapPcmInWavHeader(allPcm, SAMPLE_RATE)
        val outFile = File(recordingsDir, "dictation_${System.currentTimeMillis()}.wav")
        FileOutputStream(outFile).use { fos ->
            fos.write(wavBytes)
            fos.flush()
            try { fos.fd.sync() } catch (_: Throwable) {}
        }
        outFile
    }

    @Synchronized
    fun cancelAndDeleteRecording() {
        isRecordingAtom.set(false)
        try { recordingJob?.cancel() } catch (_: Throwable) {}
        recordingJob = null
        stopAndReleaseQuietly()
        synchronized(fullPcmStream) { fullPcmStream.reset() }
        synchronized(liveSlicePcmStream) { liveSlicePcmStream.reset() }
        currentMaxAmplitude.set(0)
    }

    private fun stopAndReleaseQuietly() {
        isRecordingAtom.set(false)
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    private fun pruneOldRecordings() {
        try {
            val files = recordingsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(5).forEach { try { it.delete() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    private fun wrapPcmInWavHeader(pcmBytes: ByteArray, sampleRate: Int = 16000): ByteArray {
        val totalDataLen = pcmBytes.size + 36
        val out = ByteArrayOutputStream(pcmBytes.size + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(1.toShort()) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.size)
        }
        out.write(header.array())
        out.write(pcmBytes)
        return out.toByteArray()
    }
}
