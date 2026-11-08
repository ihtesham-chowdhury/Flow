package com.flowbangla.app.ui

/**
 * Resolves touch coordinates into Action Pod hit targets:
 * - In RECORDING: Left circle triggers CANCEL, right circle/area triggers MAIN_OR_CONFIRM (Done)
 * - In PROCESSING: Left circle triggers CANCEL, other areas trigger NONE
 * - In IDLE: Taps trigger MAIN_OR_CONFIRM (Start recording)
 */
object ActionPodHitResolver {

    fun resolve(
        touchX: Float,
        touchY: Float,
        isRecording: Boolean,
        isProcessing: Boolean,
        cancelLeft: Float,
        cancelRight: Float,
        cancelTop: Float,
        cancelBottom: Float,
        confirmLeft: Float,
        confirmRight: Float,
        confirmTop: Float,
        confirmBottom: Float,
        slop: Float = 0f
    ): PodHitZone {
        if (isRecording || isProcessing) {
            val cLeft = cancelLeft - slop
            val cRight = cancelRight + slop
            val cTop = cancelTop - slop
            val cBottom = cancelBottom + slop

            if (touchX in cLeft..cRight && touchY in cTop..cBottom) {
                return PodHitZone.CANCEL
            }

            if (isRecording) {
                val cfLeft = confirmLeft - slop
                val cfRight = confirmRight + slop
                val cfTop = confirmTop - slop
                val cfBottom = confirmBottom + slop

                if ((touchX in cfLeft..cfRight && touchY in cfTop..cfBottom) || touchX >= cRight) {
                    return PodHitZone.MAIN_OR_CONFIRM
                }
            }

            return PodHitZone.NONE
        }

        return PodHitZone.MAIN_OR_CONFIRM
    }
}
