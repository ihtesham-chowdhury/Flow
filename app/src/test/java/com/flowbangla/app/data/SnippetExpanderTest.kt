package com.flowbangla.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetExpanderTest {

    @Test
    fun testStandaloneTriggerExpansion() {
        val snippets = listOf(
            VoiceSnippet(trigger = "my address", expansion = "House 42, Road 7, Sylhet 3100"),
            VoiceSnippet(trigger = "আমার ঠিকানা", expansion = "সিলেট, বাংলাদেশ")
        )

        // Trailing punctuation should be ignored for standalone expansion
        assertEquals("House 42, Road 7, Sylhet 3100", SnippetExpander.expand("my address.", snippets))
        assertEquals("House 42, Road 7, Sylhet 3100", SnippetExpander.expand("My Address", snippets))
        assertEquals("সিলেট, বাংলাদেশ", SnippetExpander.expand("আমার ঠিকানা।", snippets))
    }

    @Test
    fun testMidSentenceExpansion() {
        val snippets = listOf(
            VoiceSnippet(trigger = "my email", expansion = "developer@flowbangla.app")
        )

        val input = "Please forward the document to my email by tomorrow."
        val expanded = SnippetExpander.expand(input, snippets)
        assertEquals("Please forward the document to developer@flowbangla.app by tomorrow.", expanded)
    }

    @Test
    fun testLongestTriggerWins() {
        val snippets = listOf(
            VoiceSnippet(trigger = "my phone", expansion = "+8801700000000"),
            VoiceSnippet(trigger = "my phone number", expansion = "+8801711111111")
        )

        val input = "Call me on my phone number please."
        val expanded = SnippetExpander.expand(input, snippets)
        assertEquals("Call me on +8801711111111 please.", expanded)
    }

    @Test
    fun testWordBoundarySafety() {
        val snippets = listOf(
            VoiceSnippet(trigger = "me", expansion = "myself")
        )

        val input = "Welcome home dear."
        val expanded = SnippetExpander.expand(input, snippets)
        assertEquals("Welcome home dear.", expanded) // "me" in "home" or "Welcome" must NOT be expanded
    }
}
