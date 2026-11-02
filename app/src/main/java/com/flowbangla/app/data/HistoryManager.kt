package com.flowbangla.app.data

import android.content.Context
import com.flowbangla.app.model.DictationRecord
import org.json.JSONArray
import org.json.JSONObject

class HistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("flowbangla_history", Context.MODE_PRIVATE)

    @Synchronized
    fun addRecord(record: DictationRecord) {
        val list = getRecentRecords().toMutableList()
        list.add(0, record)
        if (list.size > MAX_HISTORY) {
            list.removeAt(list.size - 1)
        }
        saveList(list)
    }

    @Synchronized
    fun getRecentRecords(): List<DictationRecord> {
        val jsonString = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<DictationRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    DictationRecord(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        text = obj.getString("text"),
                        durationSeconds = obj.optInt("duration", 0),
                        isSuccess = obj.optBoolean("success", true),
                        rawTranscript = obj.optString("raw_transcript", ""),
                        provenance = obj.optString("provenance", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_RECORDS).apply()
    }

    @Synchronized
    fun deleteRecord(id: String) {
        val list = getRecentRecords().filter { it.id != id }
        saveList(list)
    }

    private fun saveList(list: List<DictationRecord>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("text", item.text)
                put("duration", item.durationSeconds)
                put("success", item.isSuccess)
                put("raw_transcript", item.rawTranscript)
                put("provenance", item.provenance)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
    }

    companion object {
        private const val KEY_RECORDS = "history_records"
        private const val MAX_HISTORY = 50
    }
}
