package com.flowbangla.app.model

data class DictationRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val durationSeconds: Int = 0,
    val isSuccess: Boolean = true,
    val rawTranscript: String = "",
    val provenance: String = ""
)
