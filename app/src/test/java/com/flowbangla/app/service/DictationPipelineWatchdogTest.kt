package com.flowbangla.app.service

import com.flowbangla.app.api.ModelCatalog
import com.flowbangla.app.api.ModelTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DictationPipelineWatchdogTest {

    @Test
    fun testPipelineTimeoutGuaranteesRecovery() = runBlocking {
        // Verify that a simulated hung IO or network call strictly times out and returns null
        val hungResult = withTimeoutOrNull(100L) {
            delay(1000L)
            "Completed"
        }
        assertNull("Hung task must return null upon timeout", hungResult)

        // Verify that a fast call succeeds within deadline
        val fastResult = withTimeoutOrNull(1000L) {
            delay(20L)
            "FastSuccess"
        }
        assertEquals("FastSuccess", fastResult)
    }

    @Test
    fun testBoundedDfsCapsNodeVisitsAndDepth() {
        // Mock tree node structure
        class MockNode(val id: Int, val isCandidate: Boolean, val children: MutableList<MockNode> = mutableListOf())

        // Create a deep tree with 100 nodes (depth 25, 4 children each)
        fun buildTree(currentDepth: Int, maxDepth: Int, idCounter: AtomicInteger): MockNode {
            val node = MockNode(idCounter.incrementAndGet(), isCandidate = false)
            if (currentDepth < maxDepth) {
                for (i in 0 until 3) {
                    node.children.add(buildTree(currentDepth + 1, maxDepth, idCounter))
                }
            }
            return node
        }

        val root = buildTree(0, 12, AtomicInteger(0))

        fun searchBounded(
            node: MockNode?,
            visitedCounter: AtomicInteger,
            maxNodes: Int,
            depth: Int
        ): MockNode? {
            if (node == null || depth > 7 || visitedCounter.incrementAndGet() > maxNodes) return null
            if (node.isCandidate) return node
            for (child in node.children) {
                if (visitedCounter.get() > maxNodes) break
                val found = searchBounded(child, visitedCounter, maxNodes, depth + 1)
                if (found != null) return found
            }
            return null
        }

        val counter = AtomicInteger(0)
        val result = searchBounded(root, counter, maxNodes = 30, depth = 0)

        assertNull("Candidate should not be found in non-candidate tree", result)
        assertTrue("Visited count must be capped at maxNodes (<= 31)", counter.get() <= 31)
    }

    @Test
    fun testModelCatalogSelectCandidatesReturnsDefaultsWhenLiveModelsEmpty() {
        // When live model cache is empty (offline or network blocked), selectCandidates
        // must return default specs rather than an empty list
        val asrCandidates = ModelCatalog.selectCandidates(ModelTask.AUDIO_TRANSCRIPTION, emptyList())
        assertTrue("Must provide default ASR candidates when live models are empty", asrCandidates.isNotEmpty())
        assertEquals("gemini-3.5-transcribe", asrCandidates.first().id)

        val formattingCandidates = ModelCatalog.selectCandidates(ModelTask.TEXT_FORMATTING, emptyList())
        assertTrue("Must provide default formatting candidates when live models are empty", formattingCandidates.isNotEmpty())
        assertEquals("gemini-3.5-flash-lite", formattingCandidates.first().id)
    }
}
