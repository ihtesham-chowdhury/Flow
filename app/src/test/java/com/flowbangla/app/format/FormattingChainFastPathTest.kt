package com.flowbangla.app.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingChainFastPathTest {

    @Test
    fun testFastPathAllowedForUltraShortCleanUtterances() {
        assertTrue(FormattingChain.shouldUseInstantLocalFastPath("Hello there", AutoCleanupLevel.MEDIUM))
        assertTrue(FormattingChain.shouldUseInstantLocalFastPath("Good morning", AutoCleanupLevel.MEDIUM))
        assertTrue(FormattingChain.shouldUseInstantLocalFastPath("Thank you", AutoCleanupLevel.LIGHT))
    }

    @Test
    fun testFastPathBlockedForLongUtterances() {
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("Hello my dear friend how are you", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("Please send the invoice by end of day", AutoCleanupLevel.MEDIUM))
    }

    @Test
    fun testFastPathBlockedForBacktrackCues() {
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("no wait", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("actually three", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("not this", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("থাক বাদ দাও", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("না ঠিক আছে", AutoCleanupLevel.MEDIUM))
    }

    @Test
    fun testFastPathBlockedForListCues() {
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("grocery list", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("number one", AutoCleanupLevel.MEDIUM))
        assertFalse(FormattingChain.shouldUseInstantLocalFastPath("এক নাম্বার", AutoCleanupLevel.MEDIUM))
    }

    @Test
    fun testFastPathAlwaysAllowedWhenCleanupLevelIsNone() {
        assertTrue(FormattingChain.shouldUseInstantLocalFastPath("Let's make a recipe no wait", AutoCleanupLevel.NONE))
    }

    @Test
    fun testSmartModeBacktrackRegexDetection() {
        val cleanSpeech = "আমি কালকে বাজারে যাব এবং কিছু ফল কিনব"
        assertFalse(FormattingChain.BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(cleanSpeech))

        val backtrackSpeech1 = "আমি কালকে যাব না না পরশু যাব"
        assertTrue(FormattingChain.BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(backtrackSpeech1))

        val backtrackSpeech2 = "Let's meet at 5, scratch that, 6 PM"
        assertTrue(FormattingChain.BACKTRACK_OR_LIST_CUE_REGEX.containsMatchIn(backtrackSpeech2))
    }
}
