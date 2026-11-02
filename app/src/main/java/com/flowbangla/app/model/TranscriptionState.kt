package com.flowbangla.app.model

sealed class TranscriptionState {
    object Idle : TranscriptionState()
    data class Recording(val durationMs: Long = 0, val amplitude: Float = 0f) : TranscriptionState()
    object Processing : TranscriptionState()
    data class Success(val text: String) : TranscriptionState()
    data class Error(val message: String) : TranscriptionState()
}

enum class InteractionMode {
    TAP_TO_TALK,   // Tap once to start, tap again to stop
    PUSH_TO_TALK   // Hold to speak, release to transcribe
}
