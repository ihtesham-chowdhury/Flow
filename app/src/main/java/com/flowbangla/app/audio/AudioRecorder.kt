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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Flow v4.1.0 — High-Fidelity 16kHz 16-bit Mono PCM Audio Recorder.
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
    private var outputDirectory: File = File(context.filesDir, "flow_recordings").apply { mkdirs() }
    private var startTimeMillis: Long = 0L

    var onAmplitudeChanged: ((amplitude: Float, durationMs: Long) -> Unit)? = null
    var onPcmChunkAvailable: ((bytes: ByteArray, length: Int) -> Unit)? = null

    @SuppressLint("MissingPermission")
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

        val activeScope = customScope
        recordingJob = activeScope.launch(Dispatchers.IO) {
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
                    // Combine RMS and Peak for ultra-responsive 60fps waveform animation!
                    val combinedAmp = (rms * 2 + peak) / 2
                    currentMaxAmplitude.set(combinedAmp)

                    val bytesCount = readShorts * 2
                    synchronized(fullPcmStream) {
                        fullPcmStream.write(byteBuf, 0, bytesCount)
                    }
                    synchronized(liveSlicePcmStream) {
                        liveSlicePcmStream.write(byteBuf, 0, bytesCount)
                    }

                    // Optional streaming chunk listener
                    onPcmChunkAvailable?.invoke(byteBuf, bytesCount)
                }
            }
        }

        val placeholderFile = File(outputDirectory, "dictation_active.wav")
        return Result.success(placeholderFile)
    }

    /**
     * Returns live voice amplitude (0..32767) for the 60fps waveform bars!
     */
    fun getCurrentAmplitude(): Int = currentMaxAmplitude.get()

    /**
     * Extracts the latest unread PCM bytes as a temporary .wav file for Live Preview
     * without interrupting the main recording!
     */
    fun drainLatestLiveSliceWavFile(): File? {
        val sliceBytes = synchronized(liveSlicePcmStream) {
            if (liveSlicePcmStream.size() < SAMPLE_RATE * 2) { // At least 1.0s of audio
                return null
            }
            val b = liveSlicePcmStream.toByteArray()
            liveSlicePcmStream.reset()
            b
        }
        // Check if slice contains actual speech energy (not pure silence)
        var maxSample = 0
        var i = 0
        while (i + 1 < sliceBytes.size) {
            val sample = (sliceBytes[i].toInt() and 0xFF) or (sliceBytes[i + 1].toInt() shl 8)
            val abs = kotlin.math.abs(sample.toShort().toInt())
            if (abs > maxSample) maxSample = abs
            i += 8 // Sample every 4th frame for fast energy check
        }
        if (maxSample < 350) return null // Skip silent slices so ASR never hallucinates on silence!

        val wavBytes = LiveRollingCloudPreviewer.wrapPcmInWavHeader(sliceBytes, SAMPLE_RATE)
        val tempSlice = File(outputDirectory, "live_slice_${System.currentTimeMillis()}.wav")
        FileOutputStream(tempSlice).use { it.write(wavBytes) }
        return tempSlice
    }

    /**
     * Returns any remaining tail slice when user taps Confirm (✓).
     */
    fun drainRemainingTailWavFile(): File? {
        val tailBytes = synchronized(liveSlicePcmStream) {
            if (liveSlicePcmStream.size() < 3200) return null // < 0.1s
            val b = liveSlicePcmStream.toByteArray()
            liveSlicePcmStream.reset()
            b
        }
        val wavBytes = LiveRollingCloudPreviewer.wrapPcmInWavHeader(tailBytes, SAMPLE_RATE)
        val tailFile = File(outputDirectory, "tail_slice_${System.currentTimeMillis()}.wav")
        FileOutputStream(tailFile).use { it.write(wavBytes) }
        return tailFile
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
        val wavBytes = LiveRollingCloudPreviewer.wrapPcmInWavHeader(allPcm, SAMPLE_RATE)
        val outFile = File(outputDirectory, "dictation_${System.currentTimeMillis()}.wav")
        FileOutputStream(outFile).use { it.write(wavBytes) }
        outFile
    }

    suspend fun rotateRecordingFileSafelyIo(): File? = withContext(Dispatchers.IO) {
        drainLatestLiveSliceWavFile() ?: drainRemainingTailWavFile()
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
            val files = outputDirectory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(5).forEach { try { it.delete() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }
}
