package com.flowbangla.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_BYTES = 3840 // 120ms of 16kHz 16-bit mono PCM
    }

    private var audioRecord: AudioRecord? = null
    private var fallbackMediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRecording = AtomicBoolean(false)

    @Volatile
    private var latestRawAmplitude: Int = 0

    var onAmplitudeChanged: ((amplitude: Float, durationMs: Long) -> Unit)? = null
    var onPcmChunkAvailable: ((bytes: ByteArray, length: Int) -> Unit)? = null
    var onRollingPauseDetected: ((segmentFile: File) -> Unit)? = null

    private fun getRecordingsDir(): File {
        val dir = File(context.filesDir, "flow_recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun pruneOldRecordings() {
        try {
            val dir = File(context.filesDir, "flow_recordings")
            if (dir.exists()) {
                val files = dir.listFiles { f -> f.extension in listOf("m4a", "wav") } ?: emptyArray()
                if (files.size > 5) {
                    files.sortByDescending { it.lastModified() }
                    for (i in 5 until files.size) {
                        try { files[i].delete() } catch (_: Throwable) {}
                    }
                }
            }
            // Clean up any old orphaned cache files
            val cacheFiles = context.cacheDir.listFiles { f ->
                (f.name.startsWith("dictation_") || f.name.startsWith("chunk_")) && f.extension in listOf("m4a", "wav")
            } ?: emptyArray()
            for (f in cacheFiles) {
                try { f.delete() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Prune recordings warning: ${t.message}")
        }
    }

    fun getCurrentAmplitude(): Int {
        return try {
            fallbackMediaRecorder?.maxAmplitude?.takeIf { it > 0 } ?: latestRawAmplitude
        } catch (_: Throwable) {
            latestRawAmplitude
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(): Result<File> {
        stopRecordingInternal()
        pruneOldRecordings()

        // 1. Primary: MediaRecorder (24kHz 64kbps AAC .m4a) for lightweight, exclusive, fast-upload audio capture
        val m4aResult = startMediaRecorderFallback()
        if (m4aResult.isSuccess) {
            return m4aResult
        }

        // 2. Secondary: Fallback to AudioRecord (WAV) if MediaRecorder is blocked
        val outputFile = File(getRecordingsDir(), "dictation_${System.currentTimeMillis()}.wav")
        currentOutputFile = outputFile
        startTimeMillis = System.currentTimeMillis()

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = minBufSize.coerceAtLeast(CHUNK_SIZE_BYTES * 4)

        var recordInstance: AudioRecord? = null
        try {
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            recordInstance = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
            if (recordInstance.state != AudioRecord.STATE_INITIALIZED) {
                recordInstance.release()
                recordInstance = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init exception: ${e.message}")
        }

        if (recordInstance != null && recordInstance.state == AudioRecord.STATE_INITIALIZED) {
            try {
                recordInstance.startRecording()
                audioRecord = recordInstance
                isRecording.set(true)
                startPcmCaptureLoop(outputFile)
                return Result.success(outputFile)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord start failed: ${e.message}")
                recordInstance.release()
                audioRecord = null
            }
        }

        return Result.failure(IllegalStateException("Failed to initialize any audio recording engine"))
    }

    private fun startPcmCaptureLoop(outputFile: File) {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            var fos: FileOutputStream? = null
            var totalPcmBytes = 0L
            var speechDurationMs = 0L
            var silenceDurationMs = 0L
            var segmentChunkFos: FileOutputStream? = null
            var segmentChunkFile: File? = null
            var segmentPcmBytes = 0L

            try {
                fos = FileOutputStream(outputFile)
                // Write 44 blank header bytes to be filled in when recording stops
                fos.write(ByteArray(44))

                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isActive && isRecording.get() && audioRecord != null) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                        totalPcmBytes += bytesRead

                        // Compute peak amplitude
                        var maxSample = 0
                        for (i in 0 until bytesRead step 2) {
                            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            val absVal = Math.abs(sample.toInt())
                            if (absVal > maxSample) maxSample = absVal
                        }
                        latestRawAmplitude = maxSample
                        val normalizedAmp = (maxSample / 32767f).coerceIn(0f, 1f)
                        val elapsed = System.currentTimeMillis() - startTimeMillis

                        withContext(Dispatchers.Main) {
                            onAmplitudeChanged?.invoke(normalizedAmp, elapsed)
                        }

                        // Stream live PCM chunk to WebSocket
                        onPcmChunkAvailable?.invoke(buffer, bytesRead)

                        // Rolling VAD chunk detection
                        val isVoiced = normalizedAmp > 0.08f
                        if (isVoiced) {
                            speechDurationMs += 120L
                            silenceDurationMs = 0L
                            if (segmentChunkFos == null) {
                                segmentChunkFile = File(getRecordingsDir(), "chunk_${System.currentTimeMillis()}.wav")
                                segmentChunkFos = FileOutputStream(segmentChunkFile)
                                segmentChunkFos?.write(ByteArray(44))
                                segmentPcmBytes = 0L
                            }
                            segmentChunkFos?.write(buffer, 0, bytesRead)
                            segmentPcmBytes += bytesRead
                        } else {
                            if (speechDurationMs >= 8000L) {
                                silenceDurationMs += 120L
                                if (silenceDurationMs >= 350L) {
                                    segmentChunkFos?.flush()
                                    segmentChunkFos?.close()
                                    segmentChunkFos = null
                                    segmentChunkFile?.let { chunkFile ->
                                        finalizeWavHeader(chunkFile, segmentPcmBytes)
                                        onRollingPauseDetected?.invoke(chunkFile)
                                    }
                                    segmentChunkFile = null
                                    speechDurationMs = 0L
                                    silenceDurationMs = 0L
                                } else if (segmentChunkFos != null) {
                                    segmentChunkFos?.write(buffer, 0, bytesRead)
                                    segmentPcmBytes += bytesRead
                                }
                            }
                        }
                    } else {
                        delay(20L)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PCM capture error: ${e.message}")
            } finally {
                try { segmentChunkFos?.close() } catch (_: Exception) {}
                try { fos?.flush(); fos?.close() } catch (_: Exception) {}
                finalizeWavHeader(outputFile, totalPcmBytes)
            }
        }
    }

    private fun finalizeWavHeader(file: File, totalAudioLen: Long) {
        if (!file.exists() || file.length() < 44L) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                writeWavHeader(raf, totalAudioLen, SAMPLE_RATE, 1, 16)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write WAV header: ${e.message}")
        }
    }

    private fun writeWavHeader(out: RandomAccessFile, totalAudioLen: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.seek(0)
        out.write(header)
    }

    private fun startMediaRecorderFallback(): Result<File> {
        val outputFile = File(getRecordingsDir(), "dictation_${System.currentTimeMillis()}.m4a")
        currentOutputFile = outputFile

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mediaRecorder.apply {
                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                } catch (e: Exception) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(24000)
                setAudioEncodingBitRate(64000)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            fallbackMediaRecorder = mediaRecorder
            isRecording.set(true)
            startAmplitudePollingJob()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder fallback failed: ${e.message}", e)
            stopRecordingInternal()
            Result.failure(e)
        }
    }

    private fun startAmplitudePollingJob() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            while (isActive && isRecording.get() && fallbackMediaRecorder != null) {
                try {
                    val maxAmp = fallbackMediaRecorder?.maxAmplitude ?: 0
                    latestRawAmplitude = maxAmp
                    val normalized = (maxAmp / 32767f).coerceIn(0f, 1f)
                    val elapsed = System.currentTimeMillis() - startTimeMillis
                    withContext(Dispatchers.Main) {
                        onAmplitudeChanged?.invoke(normalized, elapsed)
                    }
                } catch (_: Exception) {}
                delay(100L)
            }
        }
    }

    /**
     * Stop and finalize audio recording strictly inside withContext(Dispatchers.IO).
     * Guarantees zero blocking of the Android Main/Choreographer thread.
     */
    suspend fun stopRecordingSafelyIo(): File? = withContext(Dispatchers.IO) {
        try {
            isRecording.set(false)
            try {
                recordingJob?.cancel()
            } catch (_: Exception) {}
            recordingJob = null

            val recorder = fallbackMediaRecorder
            fallbackMediaRecorder = null
            if (recorder != null) {
                try {
                    recorder.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder.stop() warning (clip too short or interrupted): ${e.message}")
                } finally {
                    try { recorder.reset() } catch (_: Exception) {}
                    try { recorder.release() } catch (_: Exception) {}
                }
            }

            val ar = audioRecord
            audioRecord = null
            if (ar != null) {
                try {
                    ar.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord.stop() warning: ${e.message}")
                } finally {
                    try { ar.release() } catch (_: Exception) {}
                }
            }

            val file = currentOutputFile
            if (file != null && file.exists() && file.length() > 44L) {
                file
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping recorder on IO", t)
            null
        }
    }

    /**
     * Seamlessly rotates recording to a new chunk file in < 25ms so user continues speaking uninterrupted.
     */
    suspend fun rotateRecordingFileSafelyIo(): File? = withContext(Dispatchers.IO) {
        try {
            val finishedFile = stopRecordingSafelyIo()
            val startResult = startRecording()
            if (startResult.isFailure) {
                Log.w(TAG, "rotateRecordingFileSafelyIo: new chunk start failed: ${startResult.exceptionOrNull()?.message}")
            }
            finishedFile
        } catch (t: Throwable) {
            Log.e(TAG, "rotateRecordingFileSafelyIo failed", t)
            null
        }
    }

    @Synchronized
    fun stopRecording(): Result<RecordingResult> {
        isRecording.set(false)
        val durationMs = if (startTimeMillis > 0) System.currentTimeMillis() - startTimeMillis else 0
        val file = currentOutputFile

        return try {
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            audioRecord = null

            fallbackMediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                try { reset() } catch (_: Exception) {}
                release()
            }
            fallbackMediaRecorder = null

            try {
                recordingJob?.cancel()
            } catch (_: Exception) {}
            recordingJob = null

            if (file != null && file.exists() && file.length() > 44L) {
                Result.success(RecordingResult(file, durationMs))
            } else {
                Result.failure(IllegalStateException("No valid audio file produced"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Synchronized
    fun cancelAndDeleteRecording() {
        isRecording.set(false)
        stopRecordingInternal()
        try {
            currentOutputFile?.let {
                if (it.exists()) it.delete()
            }
        } catch (_: Exception) {}
        currentOutputFile = null
    }

    private fun stopRecordingInternal() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null

        try {
            fallbackMediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
        } catch (_: Exception) {}
        fallbackMediaRecorder = null
    }

    data class RecordingResult(
        val file: File,
        val durationMs: Long
    )
}
