package com.flowbangla.app.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBacktrackResolverTest {

    @Test
    fun testExplicitListItemBacktrack() {
        val input = "Let's make a grocery list number one tomato number two potato number three brinjals no no not brinjals but get some rice"
        val resolved = LocalBacktrackResolver.resolveOfflineBacktracks(input)
        assertFalse("Brinjals must be removed", resolved.contains("brinjals", ignoreCase = true))
        assertTrue("Rice must be preserved", resolved.contains("rice", ignoreCase = true))
        assertTrue("Tomato must be preserved", resolved.contains("tomato", ignoreCase = true))
        assertTrue("Potato must be preserved", resolved.contains("potato", ignoreCase = true))
    }

    @Test
    fun testTopicBacktrack() {
        val input = "Let's make a recipe oh no wait let's make a grocery list"
        val resolved = LocalBacktrackResolver.resolveOfflineBacktracks(input)
        assertFalse("Recipe should be removed", resolved.contains("recipe", ignoreCase = true))
        assertTrue("Grocery list should be retained", resolved.contains("grocery list", ignoreCase = true))
    }

    @Test
    fun testBanglaBacktrack() {
        val input = "আজকে বাজারে গিয়ে আলু পটল আর বেগুন না না বেগুন লাগবে না চালের বস্তা এনো"
        val resolved = LocalBacktrackResolver.resolveOfflineBacktracks(input)
        assertFalse("বেগুন should be removed", resolved.contains("বেগুন"))
        assertTrue("চালের বস্তা should be preserved", resolved.contains("চালের বস্তা"))
        assertTrue("আলু should be preserved", resolved.contains("আলু"))
    }

    @Test
    fun testValueBacktrack() {
        val input = "Let's meet at 2 actually 3 tomorrow"
        val resolved = LocalBacktrackResolver.resolveOfflineBacktracks(input)
        assertEquals("Let's meet at 3 tomorrow", resolved)
    }

    @Test
    fun testScratchThat() {
        val input = "We need to finish this report scratch that start the presentation"
        val resolved = LocalBacktrackResolver.resolveOfflineBacktracks(input)
        assertFalse("Report clause should be scratched", resolved.contains("report", ignoreCase = true))
        assertTrue("Presentation should be kept", resolved.contains("presentation", ignoreCase = true))
    }
}
