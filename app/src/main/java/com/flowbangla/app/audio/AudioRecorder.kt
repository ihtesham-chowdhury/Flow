package com.flowbangla.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0
    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var onAmplitudeChanged: ((amplitude: Float, durationMs: Long) -> Unit)? = null

    @Synchronized
    fun startRecording(): Result<File> {
        return try {
            stopRecordingInternal()

            val outputFile = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
            currentOutputFile = outputFile

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            startTimeMillis = System.currentTimeMillis()

            startAmplitudeTracking()

            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording: ${e.message}", e)
            stopRecordingInternal()
            Result.failure(e)
        }
    }

    @Synchronized
    fun stopRecording(): Result<RecordingResult> {
        amplitudeJob?.cancel()
        val durationMs = if (startTimeMillis > 0) System.currentTimeMillis() - startTimeMillis else 0
        val file = currentOutputFile

        return try {
            recorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w("AudioRecorder", "Stop error (probably too short): ${e.message}")
                }
                release()
            }
            recorder = null

            if (file != null && file.exists() && file.length() > 0) {
                Result.success(RecordingResult(file, durationMs))
            } else {
                Result.failure(IllegalStateException("No valid audio file produced"))
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recording: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun startAmplitudeTracking() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isActive && recorder != null) {
                try {
                    val maxAmp = recorder?.maxAmplitude ?: 0
                    // Normalize amplitude to 0.0 - 1.0 range
                    val normalized = (maxAmp / 32767f).coerceIn(0f, 1f)
                    val elapsed = System.currentTimeMillis() - startTimeMillis
                    withContext(Dispatchers.Main) {
                        onAmplitudeChanged?.invoke(normalized, elapsed)
                    }
                } catch (e: Exception) {
                    // Ignore amplitude polling error
                }
                delay(100)
            }
        }
    }

    private fun stopRecordingInternal() {
        amplitudeJob?.cancel()
        try {
            recorder?.release()
        } catch (e: Exception) {
            // ignore
        }
        recorder = null
    }

    data class RecordingResult(
        val file: File,
        val durationMs: Long
    )
}
