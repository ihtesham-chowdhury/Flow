package com.flowbangla.app.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WisprContextFormatterTest {

    @Test
    fun testMidSentenceLowercaseContinuation() {
        val context = ActiveFieldContext(
            appCategory = AppCategory.PERSONAL_MESSAGING,
            textBeforeCursor = "I am writing this because,",
            isMidSentenceContinuation = true,
            needsLeadingSpace = true
        )

        val input = "We need to discuss the project."
        val result = WisprContextFormatter.applyCursorBoundaryFormatting(input, context)
        assertEquals(" we need to discuss the project.", result)
    }

    @Test
    fun testAcronymAndPronounPreservedInMidSentence() {
        val context = ActiveFieldContext(
            appCategory = AppCategory.WORK_MESSAGING,
            textBeforeCursor = "Check the",
            isMidSentenceContinuation = true,
            needsLeadingSpace = true
        )

        val inputAcronym = "API endpoint documentation."
        val resultAcronym = WisprContextFormatter.applyCursorBoundaryFormatting(inputAcronym, context)
        assertEquals(" API endpoint documentation.", resultAcronym)

        val inputI = "I think this is correct."
        val resultI = WisprContextFormatter.applyCursorBoundaryFormatting(inputI, context)
        assertEquals(" I think this is correct.", resultI)
    }

    @Test
    fun testStartOfSentenceNotLowercased() {
        val context = ActiveFieldContext(
            appCategory = AppCategory.EMAIL,
            textBeforeCursor = "Thank you so much.\n\n",
            isMidSentenceContinuation = false,
            needsLeadingSpace = false
        )

        val input = "Please review the attached invoice."
        val result = WisprContextFormatter.applyCursorBoundaryFormatting(input, context)
        assertEquals("Please review the attached invoice.", result)
    }

    @Test
    fun testCriticalTokensPreservation() {
        val raw = "Please call +15550199000 or email support@example.com regarding 500 dollars."
        val formattedValid = "Please call +15550199000 or email support@example.com regarding 500 dollars."
        val formattedMissingNumber = "Please call me or email support@example.com regarding dollars."
        val formattedMissingEmail = "Please call +15550199000 or email me regarding 500 dollars."

        assertTrue(WisprContextFormatter.areCriticalTokensPreserved(raw, formattedValid))
        assertFalse(WisprContextFormatter.areCriticalTokensPreserved(raw, formattedMissingNumber))
        assertFalse(WisprContextFormatter.areCriticalTokensPreserved(raw, formattedMissingEmail))
    }
}
