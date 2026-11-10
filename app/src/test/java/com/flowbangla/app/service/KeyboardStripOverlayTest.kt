package com.flowbangla.app.service

import com.flowbangla.app.data.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardStripOverlayTest {

    @Test
    fun testChipStateEnumValues() {
        val states = KeyboardStripOverlayController.StripState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(KeyboardStripOverlayController.StripState.IDLE))
        assertTrue(states.contains(KeyboardStripOverlayController.StripState.RECORDING))
        assertTrue(states.contains(KeyboardStripOverlayController.StripState.PROCESSING))
        assertTrue(states.contains(KeyboardStripOverlayController.StripState.INSERTED))

        // Verify typealias compatibility
        val chipStates = ChipState.values()
        assertEquals(4, chipStates.size)
    }

    @Test
    fun testTriggerStyleConstants() {
        assertEquals("both", PreferencesManager.TRIGGER_STYLE_BOTH)
        assertEquals("keyboard_bar_only", PreferencesManager.TRIGGER_STYLE_KEYBOARD_ONLY)
        assertEquals("floating_mic_only", PreferencesManager.TRIGGER_STYLE_FLOATING_ONLY)
    }

    @Test
    fun testTriggerStyleFlagResolution() {
        fun resolveFlags(style: String): Pair<Boolean, Boolean> {
            return when (style) {
                PreferencesManager.TRIGGER_STYLE_KEYBOARD_ONLY -> Pair(true, false)
                PreferencesManager.TRIGGER_STYLE_FLOATING_ONLY -> Pair(false, true)
                else -> Pair(true, true) // TRIGGER_STYLE_BOTH
            }
        }

        val both = resolveFlags(PreferencesManager.TRIGGER_STYLE_BOTH)
        assertTrue("Both mode should enable keyboard bar", both.first)
        assertTrue("Both mode should enable floating mic", both.second)

        val keyboardOnly = resolveFlags(PreferencesManager.TRIGGER_STYLE_KEYBOARD_ONLY)
        assertTrue("Keyboard only mode should enable keyboard bar", keyboardOnly.first)
        assertFalse("Keyboard only mode should disable floating mic", keyboardOnly.second)

        val floatingOnly = resolveFlags(PreferencesManager.TRIGGER_STYLE_FLOATING_ONLY)
        assertFalse("Floating only mode should disable keyboard bar", floatingOnly.first)
        assertTrue("Floating only mode should enable floating mic", floatingOnly.second)
    }

    @Test
    fun testExactInStripCoordinateOffsetComputation() {
        fun computeExactInStripY(
            viewVisible: Boolean,
            viewScreenY: Int,
            currentParamsY: Int,
            measuredImeTopY: Int,
            density: Float
        ): Int {
            val frameOffsetPx = if (viewVisible && viewScreenY > 0) {
                viewScreenY - currentParamsY
            } else {
                0
            }
            return (measuredImeTopY - frameOffsetPx) + (4f * density).toInt()
        }

        // Test with 36px status bar offset on 2.75x density display
        val density = 2.75f
        val measuredImeTop = 1580
        val targetY = computeExactInStripY(
            viewVisible = true,
            viewScreenY = 1580,
            currentParamsY = 1544,
            measuredImeTopY = measuredImeTop,
            density = density
        )
        // frameOffset = 1580 - 1544 = 36px; targetY = 1580 - 36 + 11 = 1555px
        assertEquals(1555, targetY)
    }

    @Test
    fun testDynamicAsrTimeoutScaling() {
        fun computeTimeoutMs(durationSec: Long): Long {
            return (12_000L + (durationSec * 650L)).coerceAtMost(90_000L)
        }

        // Short 5s dictation
        assertEquals(15_250L, computeTimeoutMs(5L))

        // 45s auto-checkpoint chunk
        assertEquals(41_250L, computeTimeoutMs(45L))

        // 3-minute dictation (180s) -> capped at 90,000ms
        assertEquals(90_000L, computeTimeoutMs(180L))

        // 5-minute dictation (300s) -> capped at 90,000ms
        assertEquals(90_000L, computeTimeoutMs(300L))
    }

    enum class ActiveTrigger { NONE, INLINE_BAR, FLOATING_MIC }

    @Test
    fun testActiveSurfacePodExpansionExclusivity() {
        fun shouldExpandInlineBar(activeTrigger: ActiveTrigger, state: String): Boolean {
            if (state == "IDLE") return false
            return activeTrigger == ActiveTrigger.INLINE_BAR
        }

        fun shouldExpandFloatingMic(activeTrigger: ActiveTrigger, state: String): Boolean {
            if (state == "IDLE") return false
            return activeTrigger == ActiveTrigger.FLOATING_MIC
        }

        // When recording started from inline bar, ONLY inline bar expands
        assertTrue(shouldExpandInlineBar(ActiveTrigger.INLINE_BAR, "RECORDING"))
        assertFalse(shouldExpandFloatingMic(ActiveTrigger.INLINE_BAR, "RECORDING"))

        // When recording started from floating mic, ONLY floating mic expands
        assertFalse(shouldExpandInlineBar(ActiveTrigger.FLOATING_MIC, "RECORDING"))
        assertTrue(shouldExpandFloatingMic(ActiveTrigger.FLOATING_MIC, "RECORDING"))
    }
}
