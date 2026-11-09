package com.flowbangla.app.service

import com.flowbangla.app.data.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardStripOverlayTest {

    @Test
    fun testChipStateEnumValues() {
        val states = KeyboardStripOverlayController.ChipState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(KeyboardStripOverlayController.ChipState.IDLE))
        assertTrue(states.contains(KeyboardStripOverlayController.ChipState.RECORDING))
        assertTrue(states.contains(KeyboardStripOverlayController.ChipState.PROCESSING))
        assertTrue(states.contains(KeyboardStripOverlayController.ChipState.INSERTED))
    }

    @Test
    fun testTriggerStyleConstants() {
        assertEquals("both", PreferencesManager.TRIGGER_STYLE_BOTH)
        assertEquals("keyboard_bar_only", PreferencesManager.TRIGGER_STYLE_KEYBOARD_ONLY)
        assertEquals("floating_mic_only", PreferencesManager.TRIGGER_STYLE_FLOATING_ONLY)
    }

    @Test
    fun testTriggerStyleFlagResolution() {
        // Test simulation of resolution logic
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
}
