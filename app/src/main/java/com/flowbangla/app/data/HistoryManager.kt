package com.flowbangla.app.data

import android.content.Context
import com.flowbangla.app.model.DictationRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("flowbangla_history", Context.MODE_PRIVATE)

    /**
     * Called BEFORE any network call starts so a 4-5 minute recording is NEVER lost!
     * Saves audio to filesDir/voice_vault/rec_${id}.wav, retains the latest 3 recordings on disk,
     * and immediately creates a pending entry in History.
     */
    @Synchronized
    fun createPendingAudioRecord(audioFile: File, durationSeconds: Int): String {
        val vaultDir = File(context.filesDir, "voice_vault").apply { mkdirs() }
        val recordId = "rec_${System.currentTimeMillis()}"
        val ext = audioFile.extension.ifEmpty { "wav" }
        val prefsManager = PreferencesManager(context)
        val retention = prefsManager.voiceVaultRetention
        val shouldSaveAudio = retention != "never"
        val vaultFile = if (shouldSaveAudio) File(vaultDir, "$recordId.$ext") else null

        if (shouldSaveAudio && vaultFile != null) {
            try {
                audioFile.copyTo(vaultFile, overwrite = true)
            } catch (_: Throwable) {}

            try {
                val allVaultFiles = vaultDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                if (retention == "24_hours") {
                    val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
                    allVaultFiles.filter { it.lastModified() < oneDayAgo }.forEach {
                        try { it.delete() } catch (_: Throwable) {}
                    }
                } else {
                    // Default "last_3"
                    allVaultFiles.drop(3).forEach { oldFile ->
                        try { oldFile.delete() } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        val pendingRecord = DictationRecord(
            id = recordId,
            timestamp = System.currentTimeMillis(),
            text = "⏳ Processing voice recording... / অডিও প্রসেসিং হচ্ছে...",
            durationSeconds = durationSeconds,
            isSuccess = true,
            rawTranscript = "",
            provenance = "In Progress",
            audioFilePath = vaultFile?.absolutePath,
            isFailed = false
        )
        addRecord(pendingRecord)
        return recordId
    }

    @Synchronized
    fun markRecordSuccess(recordId: String, text: String, rawTranscript: String, provenance: String) {
        val list = getRecentRecords().toMutableList()
        val index = list.indexOfFirst { it.id == recordId }
        if (index != -1) {
            val old = list[index]
            list[index] = old.copy(
                text = text,
                rawTranscript = rawTranscript,
                provenance = provenance,
                isSuccess = true,
                isFailed = false
            )
            saveList(list)
        }
    }

    @Synchronized
    fun markRecordFailed(recordId: String, errorSummary: String) {
        val list = getRecentRecords().toMutableList()
        val index = list.indexOfFirst { it.id == recordId }
        if (index != -1) {
            val old = list[index]
            list[index] = old.copy(
                text = "⚠️ Transcription failed ($errorSummary) — Voice saved locally. Tap Regenerate below / লেখা তৈরি হয়নি — অডিও সংরক্ষিত আছে। নিচে রি-জেনারেট করুন।",
                isSuccess = false,
                isFailed = true
            )
            saveList(list)
        }
    }

    @Synchronized
    fun addRecord(record: DictationRecord) {
        val list = getRecentRecords().toMutableList()
        val existingIndex = list.indexOfFirst { it.id == record.id }
        if (existingIndex != -1) {
            list[existingIndex] = record
        } else {
            list.add(0, record)
        }
        if (list.size > MAX_HISTORY) {
            list.removeAt(list.size - 1)
        }
        saveList(list)
    }

    @Synchronized
    fun updateRecord(id: String, text: String, rawTranscript: String, provenance: String) {
        markRecordSuccess(id, text, rawTranscript, provenance)
    }

    @Synchronized
    fun getRecentRecords(): List<DictationRecord> {
        val jsonString = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<DictationRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val audioPath = if (obj.has("audio_file_path")) obj.optString("audio_file_path").takeIf { it.isNotBlank() } else null
                val isSuccess = obj.optBoolean("success", true)
                val isFailed = obj.optBoolean("is_failed", !isSuccess)
                list.add(
                    DictationRecord(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        text = obj.getString("text"),
                        durationSeconds = obj.optInt("duration", 0),
                        isSuccess = isSuccess,
                        rawTranscript = obj.optString("raw_transcript", ""),
                        provenance = obj.optString("provenance", ""),
                        audioFilePath = if (audioPath.isNullOrBlank()) null else audioPath,
                        isFailed = isFailed
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
        try {
            val vaultDir = File(context.filesDir, "voice_vault")
            vaultDir.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun deleteRecord(id: String) {
        val list = getRecentRecords().toMutableList()
        val itemToDelete = list.find { it.id == id }
        if (itemToDelete?.audioFilePath != null) {
            try { File(itemToDelete.audioFilePath).delete() } catch (_: Throwable) {}
        }
        val remaining = list.filter { it.id != id }
        saveList(remaining)
    }

    fun getTodayCount(): Int {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val midnightMs = cal.timeInMillis
        return getRecentRecords().count { it.timestamp >= midnightMs }
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
                put("is_failed", item.isFailed)
                put("raw_transcript", item.rawTranscript)
                put("provenance", item.provenance)
                if (item.audioFilePath != null) {
                    put("audio_file_path", item.audioFilePath)
                }
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
