package com.flowbangla.app.audio

import com.flowbangla.app.api.GeminiInteractionsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File

/**
 * Slices long dictations at natural breath pauses (>= 8s speech + 350ms silence)
 * and transcribes each completed chunk in the background WHILE the user continues speaking!
 */
class RollingChunkTranscriber(
    private val scope: CoroutineScope,
    private val geminiAdapter: GeminiInteractionsAdapter,
    private val apiKey: String,
    private val customVocab: List<String>
) {
    private val inFlightChunks = mutableListOf<Deferred<String>>()

    /**
     * Called by the audio recorder whenever a natural pause is detected after >= 8 seconds of speech.
     */
    fun submitCompletedSegmentAsync(segmentWavOrM4aFile: File) {
        val job = scope.async(Dispatchers.IO) {
            try {
                // Use native SMART mode on chunks so fillers and punctuation are cleaned in 1 pass
                val res = geminiAdapter.transcribeAudioFile(
                    audioFile = segmentWavOrM4aFile,
                    rawApiKey = apiKey,
                    customVocabulary = customVocab,
                    useVerbatimMode = false // SMART mode!
                )
                res.text.trim()
            } catch (_: Exception) {
                ""
            } finally {
                try { segmentWavOrM4aFile.delete() } catch (_: Exception) {}
            }
        }
        synchronized(inFlightChunks) {
            inFlightChunks.add(job)
        }
    }

    /**
     * Called when the user taps Confirm (✓). Awaits all already-running background chunks
     * in parallel with the final tail segment!
     */
    suspend fun awaitAllSegmentsAndJoin(finalTailTranscript: String): String {
        val jobs = synchronized(inFlightChunks) { inFlightChunks.toList() }
        val completedParts = jobs.mapNotNull {
            try { it.await().takeIf { s -> s.isNotEmpty() } } catch (_: Exception) { null }
        }.toMutableList()

        if (finalTailTranscript.isNotBlank()) {
            completedParts.add(finalTailTranscript.trim())
        }
        return completedParts.joinToString(" ").trim()
    }

    fun cancelAll() {
        synchronized(inFlightChunks) {
            inFlightChunks.forEach { it.cancel() }
            inFlightChunks.clear()
        }
    }

    fun hasPendingChunks(): Boolean = synchronized(inFlightChunks) { inFlightChunks.isNotEmpty() }
}
