package com.flowbangla.app.format

/**
 * Flow v4.3.0 — Deterministic Linguistic Sanity & Anti-Hallucination Guard.
 * Inspects ASR outputs in < 0.5ms and prevents Whisper decoder hallucinations,
 * autoregressive syllable loops, corrupted Bengali matra stacks, and stray Indic
 * script collisions from ever being presented or injected.
 */
object GibberishAndHallucinationGuard {

    // 1. Detects non-Bengali / non-Latin stray Indic and foreign scripts:
    //    Devanagari (\u0900-\u0963, \u0966-\u097F - explicitly preserving \u0964 '।' danda and \u0965 double danda!),
    //    Gurmukhi (\u0A00-\u0A7F), Gujarati (\u0A80-\u0AFF, e.g. 'ડ' \u0AA1),
    //    Oriya, Tamil, Telugu, Kannada, Malayalam, Sinhala, Thai, Tibetan, etc. (\u0A00-\u0FFF).
    //    Bengali is strictly \u0980-\u09FF.
    private val STRAY_SCRIPT_REGEX = Regex("""[\u0900-\u0963\u0966-\u097F\u0A00-\u0FFF]""")

    // 2. Detects invalid Bengali matra / hasanta sequences that NEVER occur in real Bangla words:
    //    - Dependent vowel sign (\u09BE-\u09CC) followed immediately by hasanta (\u09CD) (e.g. "ে্প্লে")
    //    - Independent vowel (\u0985-\u0994) followed immediately by hasanta (\u09CD) (e.g. "আ্লাখবে")
    //    - Double hasanta (\u09CD\u09CD)
    //    - Hasanta followed immediately by vowel sign (\u09CD[\u09BE-\u09CC])
    //    - Consecutive duplicate vowel signs (e.g. "েে" \u09C7\u09C7 in "আলেে্লো", or "াা")
    private val CORRUPTED_BENGALI_MATRA_REGEX = Regex(
        """[\u09BE-\u09CC]\u09CD|[\u0985-\u0994]\u09CD|\u09CD\u09CD|\u09CD[\u09BE-\u09CC]|([\u09BE-\u09CC])\1"""
    )

    // 3. Detects digit-letter gibberish collisions:
    //    - Bengali word glued to an ASCII digit at end: e.g. "সব0", "ক্বরে0"
    //    - Latin word glued to digits (excluding valid units/versions like 4G, 5G, 1080p, 24kHz, v3): e.g. "nw3"
    private val BENGALI_WORD_ASCII_DIGIT_REGEX = Regex("""[\u0980-\u09FF]+[0-9]+""")
    private val LATIN_WORD_DIGIT_REGEX = Regex(
        """(?<![vV])\b[a-zA-Z]{2,}\d+\b(?!(?:G|p|kHz|ms|dp|sp|MB|KB|GB|s)\b)"""
    )

    /**
     * Returns true if the transcript contains Whisper decoder hallucinations or corrupted Bengali tokens.
     */
    fun isHallucinatedOrCorrupted(transcript: String): Boolean {
        val clean = transcript.trim()
        if (clean.isEmpty()) return false

        // Check 1: Stray non-Bangla/non-English scripts (like Gujarati 'ડ', preserving Bengali danda \u0964)
        if (STRAY_SCRIPT_REGEX.containsMatchIn(clean)) return true

        // Check 2: Impossible Bengali Unicode matra/hasanta combinations ("আ্লাখবে", "ে্প্লে", "আলেে্লো")
        if (CORRUPTED_BENGALI_MATRA_REGEX.containsMatchIn(clean)) return true

        // Check 3: Glued digit tokens ("nw3", "সব0")
        if (BENGALI_WORD_ASCII_DIGIT_REGEX.containsMatchIn(clean)) return true
        if (LATIN_WORD_DIGIT_REGEX.containsMatchIn(clean)) return true

        // Check 4: Repetitive phonetic hallucination loops (e.g. "সিদ লাংলে, আ্লাখবে, এবাংলাবে, আলাই...")
        val words = clean.split(Regex("""[\s,।.?!]+""")).filter { it.length >= 2 }
        if (words.size >= 6) {
            var rhymingRun = 1
            for (i in 1 until words.size) {
                val prev = words[i - 1]
                val curr = words[i]
                val samePrefix = prev.length >= 2 && curr.length >= 2 && prev.take(2) == curr.take(2)
                val sameSuffix = prev.length >= 2 && curr.length >= 2 && prev.takeLast(2) == curr.takeLast(2)
                if (samePrefix || sameSuffix) {
                    rhymingRun++
                    if (rhymingRun >= 4) return true
                } else {
                    rhymingRun = 1
                }
            }
        }

        return false
    }

    /**
     * Strips isolated corrupted spans from an otherwise valid sentence so a mid-sentence
     * acoustic glitch never inserts gibberish into the user's text box.
     */
    fun sanitizeCorruptedTokens(transcript: String): String {
        val tokens = transcript.split(Regex("""\s+"""))
        val cleanTokens = tokens.filter { token ->
            val stripped = token.trim(',', '.', '।', '?', '!', ';', ':', '(', ')', '"', '\'')
            if (stripped.isEmpty()) true
            else {
                !STRAY_SCRIPT_REGEX.containsMatchIn(stripped) &&
                    !CORRUPTED_BENGALI_MATRA_REGEX.containsMatchIn(stripped) &&
                    !BENGALI_WORD_ASCII_DIGIT_REGEX.containsMatchIn(stripped) &&
                    !LATIN_WORD_DIGIT_REGEX.matches(stripped)
            }
        }
        return cleanTokens.joinToString(" ").replace(Regex("""\s+([,।.?!])"""), "$1").trim()
    }
}
