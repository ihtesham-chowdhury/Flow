package com.flowbangla.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalDictionaryReplacerTest {

    @Test
    fun testApplyReplacements_wholeWordMatch() {
        val rules = listOf(
            DictionaryEntry(word = "Sylhet", misspelling = "Silhet"),
            DictionaryEntry(word = "Ihtesham", misspelling = "Ehtesham")
        )

        val input = "My name is Ehtesham and I live in Silhet."
        val result = PersonalDictionaryReplacer.applyReplacements(input, rules)
        assertEquals("My name is Ihtesham and I live in Sylhet.", result)
    }

    @Test
    fun testApplyReplacements_preservesPossessiveAndSkipsContraction() {
        val rules = listOf(
            DictionaryEntry(word = "Dawn", misspelling = "Don")
        )

        // "Don's car" -> "Dawn's car", but "don't" must NOT become "dawn't"
        val input = "Don's car is blue, but don't touch it."
        val result = PersonalDictionaryReplacer.applyReplacements(input, rules)
        assertEquals("Dawn's car is blue, but don't touch it.", result)
    }

    @Test
    fun testApplyReplacements_banglaWords() {
        val rules = listOf(
            DictionaryEntry(word = "সিলেট", misspelling = "শিলহেট")
        )

        val input = "আমরা কাল শিলহেট যাচ্ছি।"
        val result = PersonalDictionaryReplacer.applyReplacements(input, rules)
        assertEquals("আমরা কাল সিলেট যাচ্ছি।", result)
    }

    @Test
    fun testLearnFromUserCorrection_detectsSingleWordEdit() {
        val pasted = "Ami Silhet thaki"
        val edited = "Ami Sylhet thaki"

        val learned = PersonalDictionaryReplacer.learnFromUserCorrection(pasted, edited)
        assertNotNull(learned)
        assertEquals("Sylhet", learned?.word)
        assertEquals("Silhet", learned?.misspelling)
    }

    @Test
    fun testLearnFromUserCorrection_ignoresUnrelatedOrDrasticChanges() {
        val pasted = "Hello world"
        val edited = "A completely different paragraph with many words"

        val learned = PersonalDictionaryReplacer.learnFromUserCorrection(pasted, edited)
        assertNull(learned)
    }
}
