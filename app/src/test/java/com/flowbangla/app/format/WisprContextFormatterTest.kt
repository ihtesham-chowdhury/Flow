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

    @Test
    fun testNoLeadingSpaceAtStartOfFieldOrLine() {
        val emptyFieldContext = ActiveFieldContext(
            appCategory = AppCategory.OTHER,
            textBeforeCursor = "",
            isMidSentenceContinuation = false,
            needsLeadingSpace = false
        )

        // Raw input with leading spaces must be stripped and never output leading space
        val inputWithSpace = "   Hello world."
        val result = WisprContextFormatter.applyCursorBoundaryFormatting(inputWithSpace, emptyFieldContext)
        assertEquals("Hello world.", result)
    }

    @Test
    fun testEmailAppCategoryMapsToGeneralDictation() {
        val docType = WisprContextFormatter.mapAppCategoryToDocumentType(AppCategory.EMAIL)
        assertEquals(DocumentType.GENERAL_DICTATION, docType)
    }

    @Test
    fun testCasualMessagingPeriodPolicy() {
        val messagingContext = ActiveFieldContext(
            appCategory = AppCategory.PERSONAL_MESSAGING,
            textBeforeCursor = "",
            isMidSentenceContinuation = false,
            needsLeadingSpace = false
        )

        // Single sentence in personal messaging: period stripped
        val single = "I will be there in 10 minutes."
        val resultSingle = WisprContextFormatter.applyCursorBoundaryFormatting(single, messagingContext, dropCasualPeriod = true)
        assertEquals("I will be there in 10 minutes", resultSingle)

        // Question mark must be preserved
        val question = "Are you coming today?"
        val resultQuestion = WisprContextFormatter.applyCursorBoundaryFormatting(question, messagingContext, dropCasualPeriod = true)
        assertEquals("Are you coming today?", resultQuestion)

        // Exclamation mark must be preserved
        val exclamation = "Awesome job!"
        val resultExclamation = WisprContextFormatter.applyCursorBoundaryFormatting(exclamation, messagingContext, dropCasualPeriod = true)
        assertEquals("Awesome job!", resultExclamation)

        // In Email, period is preserved even if dropCasualPeriod is true
        val emailContext = ActiveFieldContext(
            appCategory = AppCategory.EMAIL,
            textBeforeCursor = "",
            isMidSentenceContinuation = false,
            needsLeadingSpace = false
        )
        val emailText = "Please find the attached document."
        val resultEmail = WisprContextFormatter.applyCursorBoundaryFormatting(emailText, emailContext, dropCasualPeriod = true)
        assertEquals("Please find the attached document.", resultEmail)
    }

    @Test
    fun testEmojiContinuationBoundary() {
        // Preceded by standard emoji 😀 -> continuation lowercased with leading space
        val emojiContinuationContext = ActiveFieldContext(
            appCategory = AppCategory.PERSONAL_MESSAGING,
            textBeforeCursor = "We had fun 😀",
            isMidSentenceContinuation = true,
            needsLeadingSpace = true
        )
        val input = "And then we went home."
        val result = WisprContextFormatter.applyCursorBoundaryFormatting(input, emojiContinuationContext)
        assertEquals(" and then we went home.", result)

        // Preceded by punctuation emoji ❓ -> new capitalized sentence
        val punctEmojiContext = ActiveFieldContext(
            appCategory = AppCategory.PERSONAL_MESSAGING,
            textBeforeCursor = "Are you sure ❓",
            isMidSentenceContinuation = false,
            needsLeadingSpace = true
        )
        val inputNewSentence = "Yes I am."
        val resultNew = WisprContextFormatter.applyCursorBoundaryFormatting(inputNewSentence, punctEmojiContext)
        assertEquals(" Yes I am.", resultNew)
    }
}
