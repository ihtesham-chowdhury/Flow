package com.flowbangla.app.audio

import com.flowbangla.app.format.AutoCleanupLevel
import com.flowbangla.app.format.FormattingChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream

class LiveRollingCloudPreviewerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testWrapPcmInWavHeaderProducesValidRiffWav() {
        // 1 second of 16kHz 16-bit mono PCM = 32,000 bytes
        val pcmBytes = ByteArray(32000) { (it % 128).toByte() }
        val wavBytes = LiveRollingCloudPreviewer.wrapPcmInWavHeader(pcmBytes, sampleRate = 16000)

        // Total length must be exact 44 header bytes + PCM data
        assertEquals(32044, wavBytes.size)

        // Verify RIFF and WAVE magic bytes in header
        assertEquals('R'.code.toByte(), wavBytes[0])
        assertEquals('I'.code.toByte(), wavBytes[1])
        assertEquals('F'.code.toByte(), wavBytes[2])
        assertEquals('F'.code.toByte(), wavBytes[3])

        assertEquals('W'.code.toByte(), wavBytes[8])
        assertEquals('A'.code.toByte(), wavBytes[9])
        assertEquals('V'.code.toByte(), wavBytes[10])
        assertEquals('E'.code.toByte(), wavBytes[11])

        // Verify that AudioFileInspector detects RIFF WAVE and audio/wav
        val testFile = tempFolder.newFile("generated_slice.wav")
        FileOutputStream(testFile).use { it.write(wavBytes) }

        val meta = AudioFileInspector.inspectAndValidate(testFile)
        assertEquals("audio/wav", meta.verifiedMimeType)
        assertEquals("RIFF WAVE", meta.detectedContainer)
        assertTrue(meta.fileSizeBytes > 0)
    }

    @Test
    fun testFastPathAndBacktrackCues() {
        val shortSimple = "Hello friend"
        val hasBacktrack1 = FormattingChain.BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(shortSimple)
        assertEquals(false, hasBacktrack1)
        assertTrue(FormattingChain.shouldUseInstantLocalFastPath(shortSimple, AutoCleanupLevel.LIGHT))

        val utteranceWithBacktrack = "I went to Dhaka no actually Chittagong"
        val hasBacktrack2 = FormattingChain.BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(utteranceWithBacktrack)
        assertEquals(true, hasBacktrack2)

        val banglaBacktrack = "আমি যাব ঢাকা না থাক চট্টগ্রাম যাব"
        val hasBacktrack3 = FormattingChain.BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(banglaBacktrack)
        assertEquals(true, hasBacktrack3)
    }
}
