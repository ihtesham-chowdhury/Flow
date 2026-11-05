package com.flowbangla.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

class TextSpliceHelperTest {

    @Test
    fun testResolveEffectiveText_whenIsHintIsTrue_returnsEmpty() {
        val result = TextSpliceHelper.resolveEffectiveText(
            rawExisting = "Add a comment...",
            isHint = true,
            hintText = "Add a comment..."
        )
        assertEquals("", result)
    }

    @Test
    fun testResolveEffectiveText_whenRawMatchesHint_returnsEmpty() {
        val result = TextSpliceHelper.resolveEffectiveText(
            rawExisting = "কমেন্ট লিখুন...",
            isHint = false,
            hintText = "কমেন্ট লিখুন..."
        )
        assertEquals("", result)
    }

    @Test
    fun testResolveEffectiveText_whenUserHasTypedContent_returnsUserText() {
        val result = TextSpliceHelper.resolveEffectiveText(
            rawExisting = "আমার সোনার বাংলা",
            isHint = false,
            hintText = "কমেন্ট লিখুন..."
        )
        assertEquals("আমার সোনার বাংলা", result)
    }

    @Test
    fun testSpliceText_intoEmptyField_returnsNewTextAndCursorAtEnd() {
        val res = TextSpliceHelper.spliceText(
            actualExisting = "",
            newText = "Hello world",
            selStart = 0,
            selEnd = 0
        )
        assertEquals("Hello world", res.combinedText)
        assertEquals(11, res.newCursorPos)
    }

    @Test
    fun testSpliceText_appendWithSpace_whenExistingDoesNotEndInWhitespace() {
        val res = TextSpliceHelper.spliceText(
            actualExisting = "Hello",
            newText = "world",
            selStart = -1,
            selEnd = -1
        )
        assertEquals("Hello world", res.combinedText)
        assertEquals(11, res.newCursorPos)
    }

    @Test
    fun testSpliceText_insertAtCursorMiddle() {
        val existing = "Hello world"
        val res = TextSpliceHelper.spliceText(
            actualExisting = existing,
            newText = "brave",
            selStart = 6,
            selEnd = 6
        )
        assertEquals("Hello brave world", res.combinedText)
        assertEquals(11, res.newCursorPos)
    }

    @Test
    fun testSpliceText_replaceSelectedText() {
        val existing = "The quick brown fox"
        // Replace "brown" (indices 10..15) with "red"
        val res = TextSpliceHelper.spliceText(
            actualExisting = existing,
            newText = "red",
            selStart = 10,
            selEnd = 15
        )
        assertEquals("The quick red fox", res.combinedText)
        assertEquals(13, res.newCursorPos)
    }

    @Test
    fun testBengaliNfcNormalization_preservesMatrasAndHasanta() {
        val rawBangla = "আমি বাংলায় গান গাই।"
        val normalized = Normalizer.normalize(rawBangla, Normalizer.Form.NFC)
        val res = TextSpliceHelper.spliceText(
            actualExisting = "",
            newText = normalized,
            selStart = 0,
            selEnd = 0
        )
        assertEquals(normalized, res.combinedText)
        assertEquals(normalized.length, res.newCursorPos)
    }
}
