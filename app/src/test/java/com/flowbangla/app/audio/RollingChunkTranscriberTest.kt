package com.flowbangla.app.audio

import com.flowbangla.app.api.GeminiInteractionsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RollingChunkTranscriberTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val adapter = GeminiInteractionsAdapter(OkHttpClient())

    @Test
    fun testEmptySegmentsJoinOnlyTail() = runBlocking {
        val chunker = RollingChunkTranscriber(scope, adapter, "test-api-key", emptyList())
        assertFalse(chunker.hasPendingChunks())

        val result = chunker.awaitAllSegmentsAndJoin("Final tail speech sentence.")
        assertEquals("Final tail speech sentence.", result)
    }

    @Test
    fun testCancelAllClearsPendingChunks() = runBlocking {
        val chunker = RollingChunkTranscriber(scope, adapter, "test-api-key", emptyList())
        chunker.cancelAll()
        assertFalse(chunker.hasPendingChunks())

        val joined = chunker.awaitAllSegmentsAndJoin("")
        assertEquals("", joined)
    }
}
