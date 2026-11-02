package com.flowbangla.app.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPostProcessorTest {

    @Test
    fun testPunctuationSpacingAndDanda() {
        val input = "আমি কাল যাব । তুমি কি আসবে ?"
        val expected = "আমি কাল যাব। তুমি কি আসবে?"
        val actual = TranscriptPostProcessor.process(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testCollapseDuplicatePunctuation() {
        val input = "সত্যি কথা।। আমি যাব,, তুমি?? ঠিক আছে!!"
        val expected = "সত্যি কথা। আমি যাব, তুমি? ঠিক আছে!"
        val actual = TranscriptPostProcessor.process(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testSpokenCommands() {
        val input = "প্রথম কাজ নতুন লাইন দ্বিতীয় কাজ নতুন অনুচ্ছেদ তৃতীয় কাজ দাঁড়ি"
        val actual = TranscriptPostProcessor.process(input)
        assertTrue("Expected newline between first and second, got: '$actual'", actual.contains("প্রথম কাজ\n"))
        assertTrue("Expected dari at end of third, got: '$actual'", actual.endsWith("।"))
    }

    @Test
    fun testSpokenCommandsEnglish() {
        val input = "Hello comma how are you question mark new line I am fine period"
        val expected = "Hello, how are you?\nI am fine."
        val actual = TranscriptPostProcessor.process(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testContextAwareFillerCleaning() {
        // Sentence-initial hesitation filler "মানে," should be stripped
        val input1 = "মানে, আমি কালকে যাব।"
        val actual1 = TranscriptPostProcessor.process(input1)
        assertEquals("আমি কালকে যাব।", actual1)

        // Lexical meaning word "এর মানে কী" should be preserved!
        val input2 = "এর মানে কী?"
        val actual2 = TranscriptPostProcessor.process(input2)
        assertEquals("এর মানে কী?", actual2)
    }

    @Test
    fun testStutterCollapse() {
        val input = "I I will go to to the office"
        val expected = "I will go to the office."
        val actual = TranscriptPostProcessor.process(input)
        assertEquals("I will go to the office", actual)
    }

    @Test
    fun testNumeralConversion() {
        val input = "মিটিং 10 টায়, রুম 305"
        val bengaliResult = TranscriptPostProcessor.process(input, NumeralPreference.BENGALI)
        assertEquals("মিটিং ১০ টায়, রুম ৩০৫".replace("য়", "য়"), bengaliResult)

        val westernResult = TranscriptPostProcessor.process("মিটিং ১০ টায়, রুম ৩০৫", NumeralPreference.WESTERN)
        assertEquals("মিটিং 10 টায়, রুম 305".replace("য়", "য়"), westernResult)
    }

    @Test
    fun testBulletPointSpokenCommand() {
        val input = "কাজের তালিকা বুলেট পয়েন্ট দুধ কিনব বুলেট পয়েন্ট বিল দেব"
        val actual = TranscriptPostProcessor.process(input)
        assertTrue(actual.contains("• দুধ কিনব"))
        assertTrue(actual.contains("• বিল দেব"))
    }
}
