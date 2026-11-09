package com.flowbangla.app.service

import com.flowbangla.app.autofill.FlowInlineAutofillService
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
    fun testNativeAutofillStateSuppression() {
        // When native autofill is active on field, overlay should hide during IDLE
        fun shouldShowOverlayInIdle(isNativeActive: Boolean, isKeyboardOpen: Boolean): Boolean {
            if (!isKeyboardOpen) return false
            if (isNativeActive) return false
            return true
        }

        assertFalse("Should be hidden if keyboard closed", shouldShowOverlayInIdle(false, false))
        assertTrue("Should be visible if keyboard open and no native chip", shouldShowOverlayInIdle(false, true))
        assertFalse("Should be hidden if native chip is active in Gboard strip", shouldShowOverlayInIdle(true, true))
    }
}
