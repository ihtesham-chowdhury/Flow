package com.flowbangla.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VoiceSnippet(
    val trigger: String,    // Spoken trigger phrase (max 60 chars, trimmed)
    val expansion: String,  // Replacement block (up to 4,000 chars)
    val updatedAtMs: Long = System.currentTimeMillis()
)

object SnippetExpander {

    /**
     * Expands spoken triggers both when spoken alone (ignoring trailing punctuation)
     * and when spoken inside a sentence. Longest trigger by character count wins.
     */
    fun expand(transcript: String, snippets: List<VoiceSnippet>): String {
        if (transcript.isBlank() || snippets.isEmpty()) return transcript

        val sorted = snippets
            .sortedWith(compareByDescending<VoiceSnippet> { it.trigger.length }.thenByDescending { it.updatedAtMs })

        // 1. Check if the entire utterance (ignoring trailing punctuation) matches a snippet trigger
        val strippedUtterance = transcript.trim().trimEnd('.', '।', '?', '!', ',', ' ')
        for (snippet in sorted) {
            if (strippedUtterance.equals(snippet.trigger, ignoreCase = true)) {
                return snippet.expansion
            }
        }

        // 2. Mid-sentence expansion (longest match wins, word boundaries)
        var result = transcript
        for (snippet in sorted) {
            val escaped = Regex.escape(snippet.trigger)
            val regex = Regex("""(?<![\p{L}\d_])($escaped)(?![\p{L}\d_])""", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { snippet.expansion }
        }
        return result
    }
}

class SnippetManager(context: Context) {

    private val prefs = context.getSharedPreferences("flow_voice_snippets", Context.MODE_PRIVATE)

    fun getAllSnippets(): List<VoiceSnippet> {
        val raw = prefs.getString("snippets_json", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { idx ->
            val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
            val trig = obj.optString("trigger", "").trim().take(60)
            val exp = obj.optString("expansion", "").trim().take(4000)
            if (trig.isEmpty() || exp.isEmpty()) return@mapNotNull null
            VoiceSnippet(trig, exp, obj.optLong("updatedAtMs", 0L))
        }
    }

    fun addOrUpdateSnippet(trigger: String, expansion: String) {
        val cleanTrig = trigger.trim().take(60)
        val cleanExp = expansion.trim().take(4000)
        if (cleanTrig.isEmpty() || cleanExp.isEmpty()) return

        val existing = getAllSnippets().toMutableList()
        existing.removeAll { it.trigger.equals(cleanTrig, ignoreCase = true) }
        existing.add(0, VoiceSnippet(cleanTrig, cleanExp, System.currentTimeMillis()))
        saveSnippets(existing)
    }

    fun deleteSnippet(trigger: String) {
        val cleanTrig = trigger.trim()
        val existing = getAllSnippets().toMutableList()
        val removed = existing.removeAll { it.trigger.equals(cleanTrig, ignoreCase = true) }
        if (removed) {
            saveSnippets(existing)
        }
    }

    fun expandSnippets(transcript: String): String {
        return SnippetExpander.expand(transcript, getAllSnippets())
    }

    private fun saveSnippets(list: List<VoiceSnippet>) {
        val arr = JSONArray()
        list.take(200).forEach { item ->
            arr.put(JSONObject().apply {
                put("trigger", item.trigger)
                put("expansion", item.expansion)
                put("updatedAtMs", item.updatedAtMs)
            })
        }
        prefs.edit().putString("snippets_json", arr.toString()).apply()
    }
}
