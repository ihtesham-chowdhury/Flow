package com.flowbangla.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

data class DictionaryEntry(
    val word: String,                  // Correct spelling (max 60 chars, trimmed)
    val misspelling: String? = null,   // Optional wrong spelling that Flow produced
    val autoLearned: Boolean = false,  // True if learned automatically ✨
    val updatedAtMs: Long = System.currentTimeMillis()
)

object PersonalDictionaryReplacer {

    fun applyReplacements(input: String, rules: List<DictionaryEntry>): String {
        val sortedRules = rules
            .filter { !it.misspelling.isNullOrBlank() }
            .sortedWith(compareByDescending<DictionaryEntry> { it.misspelling!!.length }.thenByDescending { it.updatedAtMs })

        var result = input
        for (rule in sortedRules) {
            val wrong = Regex.escape(rule.misspelling!!)
            // Match whole word, allowing trailing possessive ('s / ’s) but NOT n't contractions
            val regex = Regex("""(?<![\p{L}\d_])($wrong)(?!['’]t\b)(?=(?:['’]s\b)|(?![\p{L}\d_]))""", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { matchResult ->
                rule.word
            }
        }
        return result
    }

    fun learnFromUserCorrection(pastedText: String, editedText: String): DictionaryEntry? {
        val oldTokens = tokenizeWords(pastedText)
        val newTokens = tokenizeWords(editedText)
        if (oldTokens.size != newTokens.size || oldTokens.isEmpty()) return null

        for (i in oldTokens.indices) {
            val oldW = oldTokens[i]
            val newW = newTokens[i]
            if (oldW != newW && !oldW.equals(newW, ignoreCase = true)) {
                val dist = levenshtein(oldW.lowercase(), newW.lowercase())
                if (dist in 1..3 && newW.length >= 3 && !newW.all { it.isDigit() }) {
                    return DictionaryEntry(word = newW, misspelling = oldW, autoLearned = true)
                }
            }
        }
        return null
    }

    fun tokenizeWords(text: String): List<String> =
        Regex("""[\p{L}\u0980-\u09FF]{2,}""").findAll(text).map { it.value }.toList()

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + min(prev, min(dp[j], dp[j - 1]))
                prev = temp
            }
        }
        return dp[b.length]
    }
}

class PersonalDictionaryManager(context: Context) {

    companion object {
        val DEFAULT_ENTRIES = listOf(
            DictionaryEntry(word = "Flow app", misspelling = "floor app"),
            DictionaryEntry(word = "Wispr Flow", misspelling = "whisper flow"),
            DictionaryEntry(word = "Flow", misspelling = "floor")
        )
    }

    private val prefs = context.getSharedPreferences("flow_personal_dictionary", Context.MODE_PRIVATE)

    fun getAllEntries(): List<DictionaryEntry> {
        val raw = prefs.getString("entries_json", null)
        if (raw == null) {
            saveEntries(DEFAULT_ENTRIES)
            return DEFAULT_ENTRIES
        }
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val loaded = (0 until arr.length()).mapNotNull { idx ->
            val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
            val w = obj.optString("word", "").trim().take(60)
            if (w.isEmpty()) return@mapNotNull null
            val m = obj.optString("misspelling", "").trim().take(60).ifEmpty { null }
            DictionaryEntry(
                word = w,
                misspelling = m,
                autoLearned = obj.optBoolean("autoLearned", false),
                updatedAtMs = obj.optLong("updatedAtMs", 0L)
            )
        }.toMutableList()

        for (defaultEntry in DEFAULT_ENTRIES) {
            if (loaded.none { it.word.equals(defaultEntry.word, ignoreCase = true) && (it.misspelling ?: "").equals(defaultEntry.misspelling ?: "", ignoreCase = true) }) {
                loaded.add(defaultEntry)
            }
        }
        return loaded
    }

    fun addOrUpdateEntry(word: String, misspelling: String? = null, autoLearned: Boolean = false) {
        val cleanWord = word.trim().take(60)
        val cleanMisspelling = misspelling?.trim()?.take(60)?.ifEmpty { null }
        if (cleanWord.isEmpty()) return

        val existing = getAllEntries().toMutableList()
        existing.removeAll {
            it.word.equals(cleanWord, ignoreCase = true) &&
                (it.misspelling ?: "").equals(cleanMisspelling ?: "", ignoreCase = true)
        }
        existing.add(0, DictionaryEntry(cleanWord, cleanMisspelling, autoLearned))
        saveEntries(existing)
    }

    fun deleteEntry(word: String, misspelling: String? = null) {
        val cleanWord = word.trim()
        val cleanMisspelling = misspelling?.trim()?.ifEmpty { null }
        val existing = getAllEntries().toMutableList()
        existing.removeAll {
            it.word.equals(cleanWord, ignoreCase = true) &&
                (it.misspelling ?: "").equals(cleanMisspelling ?: "", ignoreCase = true)
        }
        saveEntries(existing)
    }

    /**
     * Returns all correct words + screen proper nouns for Gemini 3.5 Transcribe `custom_vocabulary`.
     */
    fun buildCustomVocabularyList(screenProperNouns: List<String> = emptyList()): List<String> {
        val userWords = getAllEntries().map { it.word }
        return (userWords + screenProperNouns).distinct().take(250)
    }

    /**
     * Applies deterministic Misspelling -> Correct Spelling replacements.
     * Longest phrase match wins; preserves possessives ('s) while skipping n't contractions.
     */
    fun applyReplacements(input: String): String {
        return PersonalDictionaryReplacer.applyReplacements(input, getAllEntries())
    }

    /**
     * Auto-Learn Engine: Compares pasted text vs user-edited text in the active text field.
     * If a single word was corrected (Levenshtein distance 1..3), learns the correction!
     */
    fun learnFromUserCorrection(pastedText: String, editedText: String): DictionaryEntry? {
        val learned = PersonalDictionaryReplacer.learnFromUserCorrection(pastedText, editedText)
        if (learned != null) {
            addOrUpdateEntry(word = learned.word, misspelling = learned.misspelling, autoLearned = true)
        }
        return learned
    }

    private fun saveEntries(list: List<DictionaryEntry>) {
        val arr = JSONArray()
        list.take(1000).forEach { entry ->
            arr.put(JSONObject().apply {
                put("word", entry.word)
                if (entry.misspelling != null) put("misspelling", entry.misspelling)
                put("autoLearned", entry.autoLearned)
                put("updatedAtMs", entry.updatedAtMs)
            })
        }
        prefs.edit().putString("entries_json", arr.toString()).apply()
    }
}
