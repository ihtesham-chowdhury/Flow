package com.flowbangla.app.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GibberishAndHallucinationGuardTest {

    @Test
    fun testCorruptedBengaliMatraAndRhymeLoopFromScreenshotIsDetected() {
        // Exact hallucinated string from user screenshot Screenshot_20261115-225632_Google.png
        val hallucination1 = "সিদ লাংলে, আ্লাখবে, এবাংলাবে, আলাই, আই দলাবেনে এবেবে আলাংলে, আলেে্লো ে্প্লে বেরে, আলের আলে, একালে ঐলেদে আলে"
        assertTrue(
            "Whisper decoder Bengali hallucination loop must be detected",
            GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(hallucination1)
        )
    }

    @Test
    fun testDigitWordGlueAndStrayScriptFromUserPromptIsDetected() {
        // String from user prompt containing glued digits ("nw3", "সব0") and corrupted tokens
        val hallucination2 = "nw3 বে বর লবেড ও বেড ক্বরে সব0 ক্বরে।"
        assertTrue(
            "Digit-glued words and script corruption must be detected",
            GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(hallucination2)
        )
    }

    @Test
    fun testCorruptedBengaliMatraSequencesDetected() {
        // "আ্লাখবে" contains independent vowel আ followed by hasanta ্
        assertTrue(GibberishAndHallucinationGuard.isHallucinatedOrCorrupted("আ্লাখবে"))
        // "ে্প্লে" contains vowel sign ে followed by hasanta ্
        assertTrue(GibberishAndHallucinationGuard.isHallucinatedOrCorrupted("ে্প্লে"))
        // "আলেে্লো" contains double vowel sign েে
        assertTrue(GibberishAndHallucinationGuard.isHallucinatedOrCorrupted("আলেে্লো"))
    }

    @Test
    fun testStrayIndicScriptsDetected() {
        // Gujarati 'ડ' (\u0AA1) or Devanagari in Bengali context
        val textWithGujarati = "আমরা \u0AA1 লবেড যাব"
        assertTrue(GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(textWithGujarati))
    }

    @Test
    fun testValidBengaliAndEnglishSentencesPassCleanly() {
        val s1 = "আমি বাংলায় গান গাই।"
        assertFalse(s1, GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(s1))

        val s2 = "The quick brown fox jumps over the lazy dog."
        assertFalse(s2, GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(s2))

        val s3 = "This is a test of voice dictation with technical terms like 4G, 5G, and 1080p."
        assertFalse(s3, GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(s3))

        val s4 = "আজকের মিটিং এ আমরা ৩টি বিষয়ে আলোচনা করব, please review the notes."
        val isCorrupt = GibberishAndHallucinationGuard.isHallucinatedOrCorrupted(s4)
        if (isCorrupt) {
            println("DEBUG S4 chars: " + s4.map { "${it} (\\u%04x)".format(it.code) }.joinToString(" "))
        }
        assertFalse(s4, isCorrupt)
    }

    @Test
    fun testSanitizeCorruptedTokensStripsCorruptedWords() {
        val mixed = "Hello world nw3 testing আ্লাখবে dictation"
        val sanitized = GibberishAndHallucinationGuard.sanitizeCorruptedTokens(mixed)
        assertFalse(sanitized.contains("nw3"))
        assertFalse(sanitized.contains("আ্লাখবে"))
        assertTrue(sanitized.contains("Hello world testing dictation"))
    }
}
