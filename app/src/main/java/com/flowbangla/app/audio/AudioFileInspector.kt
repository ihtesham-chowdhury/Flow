package com.flowbangla.app.audio

import com.flowbangla.app.api.ErrorCategory
import com.flowbangla.app.api.FlowApiException
import java.io.File
import java.io.FileInputStream

data class AudioIntegrityMetadata(
    val fileSizeBytes: Long,
    val detectedContainer: String,
    val verifiedMimeType: String
)

object AudioFileInspector {

    // Official supported MIME strings for gemini-3.5-transcribe
    val SUPPORTED_GEMINI_AUDIO_MIMES = setOf(
        "audio/wav", "audio/mp3", "audio/aiff", "audio/aac", "audio/ogg",
        "audio/flac", "audio/mpeg", "audio/m4a", "audio/l16", "audio/opus",
        "audio/alaw", "audio/mulaw", "audio/webm"
    )

    fun inspectAndValidate(file: File): AudioIntegrityMetadata {
        if (!file.exists() || file.length() < 256L) {
            throw FlowApiException(
                httpCode = 0,
                category = ErrorCategory.AUDIO_EMPTY_OR_SILENT,
                detail = "Recorded audio file is missing or under 256 bytes (${file.length()} B)."
            )
        }

        val header = ByteArray(12)
        val readCount = FileInputStream(file).use { it.read(header) }
        if (readCount < 12) {
            throw FlowApiException(0, ErrorCategory.AUDIO_FILE_INVALID, "Could not read 12-byte audio header.")
        }

        // 1. Check ISO Base Media File Format (MP4 / M4A): bytes 4..7 == "ftyp"
        val isFtyp = header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()

        // 2. Check RIFF WAVE: bytes 0..3 == "RIFF" and bytes 8..11 == "WAVE"
        val isWav = header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'A'.code.toByte() &&
            header[10] == 'V'.code.toByte() &&
            header[11] == 'E'.code.toByte()

        // 3. Check Ogg container: bytes 0..3 == "OggS"
        val isOgg = header[0] == 'O'.code.toByte() &&
            header[1] == 'g'.code.toByte() &&
            header[2] == 'g'.code.toByte() &&
            header[3] == 'S'.code.toByte()

        // 4. Check ADTS AAC stream: syncword 0xFFF (byte[0] == 0xFF and (byte[1] & 0xF0) == 0xF0)
        val isAdtsAac = (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xF6) == 0xF0

        // 5. Check MP3 (ID3 tag or MPEG sync)
        val isMp3 = (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
            ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0)

        val (container, mime) = when {
            isFtyp -> "MP4/M4A (ftyp)" to "audio/m4a" // MUST be audio/m4a for gemini-3.5-transcribe
            isWav -> "RIFF WAVE" to "audio/wav"
            isOgg -> "Ogg" to "audio/ogg"
            isAdtsAac -> "ADTS AAC" to "audio/aac"
            isMp3 -> "MP3" to "audio/mp3"
            else -> throw FlowApiException(
                httpCode = 0,
                category = ErrorCategory.AUDIO_MIME_MISMATCH,
                detail = "Unrecognized audio file header magic bytes."
            )
        }

        if (mime !in SUPPORTED_GEMINI_AUDIO_MIMES) {
            throw FlowApiException(0, ErrorCategory.AUDIO_MIME_MISMATCH, "Unsupported MIME: $mime")
        }

        return AudioIntegrityMetadata(
            fileSizeBytes = file.length(),
            detectedContainer = container,
            verifiedMimeType = mime
        )
    }
}
