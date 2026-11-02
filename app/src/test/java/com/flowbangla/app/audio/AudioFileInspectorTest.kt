package com.flowbangla.app.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class AudioFileInspectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testInspectFtypM4a() {
        val file = tempFolder.newFile("test.m4a")
        // Write 300 bytes with ftyp at bytes 4..7
        val bytes = ByteArray(300)
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        FileOutputStream(file).use { it.write(bytes) }

        val meta = AudioFileInspector.inspectAndValidate(file)
        assertEquals("audio/m4a", meta.verifiedMimeType)
        assertEquals("MP4/M4A (ftyp)", meta.detectedContainer)
    }

    @Test
    fun testInspectRiffWav() {
        val file = tempFolder.newFile("test.wav")
        // Write 300 bytes with RIFF at 0..3 and WAVE at 8..11
        val bytes = ByteArray(300)
        bytes[0] = 'R'.code.toByte()
        bytes[1] = 'I'.code.toByte()
        bytes[2] = 'F'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[8] = 'W'.code.toByte()
        bytes[9] = 'A'.code.toByte()
        bytes[10] = 'V'.code.toByte()
        bytes[11] = 'E'.code.toByte()
        FileOutputStream(file).use { it.write(bytes) }

        val meta = AudioFileInspector.inspectAndValidate(file)
        assertEquals("audio/wav", meta.verifiedMimeType)
        assertEquals("RIFF WAVE", meta.detectedContainer)
    }
}
