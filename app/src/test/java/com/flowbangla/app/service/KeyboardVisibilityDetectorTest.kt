package com.flowbangla.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardVisibilityDetectorTest {

    @Test
    fun testRestrictedBankingAndSensitivePackages() {
        assertTrue(KeyboardVisibilityDetector.isRestrictedPackage("com.bKash.customerapp"))
        assertTrue(KeyboardVisibilityDetector.isRestrictedPackage("com.konasl.nagad"))
        assertTrue(KeyboardVisibilityDetector.isRestrictedPackage("com.bitwarden.mobile"))
        assertTrue(KeyboardVisibilityDetector.isRestrictedPackage("com.agilebits.onepassword"))
        assertTrue(KeyboardVisibilityDetector.isRestrictedPackage("com.google.android.apps.walletnfcrel"))

        assertFalse(KeyboardVisibilityDetector.isRestrictedPackage("com.whatsapp"))
        assertFalse(KeyboardVisibilityDetector.isRestrictedPackage("com.google.android.youtube"))
        assertFalse(KeyboardVisibilityDetector.isRestrictedPackage("com.facebook.katana"))
    }

    @Test
    fun testKnownKeyboardPackages() {
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("com.google.android.inputmethod.latin")) // Gboard
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("com.samsung.android.honeyboard")) // Samsung
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("net.hasnath.android.keyboard")) // Ridmik
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("com.touchtype.swiftkey")) // SwiftKey
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("com.mayabi.mayabikeyboard")) // Mayabi
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("com.xiaomi.inputmethod")) // Xiaomi Mint
        assertTrue(KeyboardVisibilityDetector.isKnownKeyboard("com.baidu.input_mi")) // Xiaomi MIUI

        assertFalse(KeyboardVisibilityDetector.isKnownKeyboard("com.google.android.youtube"))
        assertFalse(KeyboardVisibilityDetector.isKnownKeyboard("com.android.chrome"))
    }

    @Test
    fun testKeyboardHeightThreshold() {
        // 100dp at density 2.0 = 200px
        assertTrue(KeyboardVisibilityDetector.isKeyboardHeightValid(250, 2.0f))
        assertTrue(KeyboardVisibilityDetector.isKeyboardHeightValid(200, 2.0f))
        assertFalse(KeyboardVisibilityDetector.isKeyboardHeightValid(150, 2.0f))
        assertFalse(KeyboardVisibilityDetector.isKeyboardHeightValid(1, 2.0f)) // hidden 1px IME stub
    }

    @Test
    fun testSignal1TypeInputMethodWindow() {
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = true,
            typeInputMethodHeightPx = 600,
            typeInputMethodBottomPx = 2400,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = false,
            hasActiveEditableFocus = false,
            appWindowBottomGapPx = 0,
            hasAppWindow = true,
            msSinceLastTouch = -1L
        )

        assertTrue(snapshot.isKeyboardVisible)
        assertTrue(snapshot.isPhysicalKeyboardOnScreen)
        assertEquals("SIGNAL_1_TYPE_INPUT_METHOD", snapshot.matchedSignal)
        assertEquals(600, snapshot.keyboardHeightPx)
        assertFalse(snapshot.isRestrictedFieldOrApp)
    }

    @Test
    fun testSignal2KnownKeyboardPackage() {
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = false,
            typeInputMethodHeightPx = 0,
            typeInputMethodBottomPx = 0,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = true,
            knownKeyboardHeightPx = 550,
            isImeInputStarted = false,
            hasActiveEditableFocus = false,
            appWindowBottomGapPx = 0,
            hasAppWindow = true,
            msSinceLastTouch = -1L
        )

        assertTrue(snapshot.isKeyboardVisible)
        assertTrue(snapshot.isPhysicalKeyboardOnScreen)
        assertEquals("SIGNAL_2_KEYBOARD_PKG", snapshot.matchedSignal)
        assertEquals(550, snapshot.keyboardHeightPx)
    }

    @Test
    fun testSignal3ImeInputStartedOnHyperOsWhenWindowsEmpty() {
        // Simulates Xiaomi HyperOS after Recents swipe: windows list is empty / mask flags,
        // but onStartInput() fired with real EditorInfo and user is focused on an editable box!
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = false,
            typeInputMethodHeightPx = 0,
            typeInputMethodBottomPx = 0,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = true,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 0,
            hasAppWindow = false,
            msSinceLastTouch = -1L
        )

        assertTrue("Keyboard MUST be detected on HyperOS via onStartInput even when windows is empty!", snapshot.isKeyboardVisible)
        assertFalse("Physical keyboard is NOT on screen when bottom gap is 0!", snapshot.isPhysicalKeyboardOnScreen)
        assertEquals("SIGNAL_3_IME_INPUT_STARTED", snapshot.matchedSignal)
    }

    @Test
    fun testPhysicalKeyboardImmediateDismissalWhenKeyboardHeightLessThan115dp() {
        // User closes keyboard (height drops below 115dp, e.g. 100px = 50dp at density 2.0)
        // even if isImeInputStarted == true and hasActiveEditableFocus == true
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = true,
            typeInputMethodHeightPx = 100, // < 115dp (230px at density 2.0)
            typeInputMethodBottomPx = 2400,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = true,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 0,
            hasAppWindow = true,
            msSinceLastTouch = 100L
        )

        assertFalse("isPhysicalKeyboardOnScreen MUST be false when keyboard window height < 115dp!", snapshot.isPhysicalKeyboardOnScreen)
    }

    @Test
    fun testSignal4ApplicationWindowBottomCompression() {
        // App window is pushed up by 600px (300dp at density 2.0)
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = false,
            typeInputMethodHeightPx = 0,
            typeInputMethodBottomPx = 0,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = false,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 600,
            hasAppWindow = true,
            msSinceLastTouch = -1L
        )

        assertTrue(snapshot.isKeyboardVisible)
        assertEquals("SIGNAL_4_WINDOW_BOTTOM_COMPRESSED", snapshot.matchedSignal)
        assertEquals(600, snapshot.keyboardHeightPx)
    }

    @Test
    fun testMeasuredImeWindowFlags() {
        val sig1 = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = true,
            typeInputMethodHeightPx = 600,
            typeInputMethodBottomPx = 2400,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = false,
            hasActiveEditableFocus = false,
            appWindowBottomGapPx = 0,
            hasAppWindow = true,
            msSinceLastTouch = -1L
        )
        assertTrue(sig1.isMeasuredImeWindow)

        val sig3 = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = false,
            typeInputMethodHeightPx = 0,
            typeInputMethodBottomPx = 0,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = true,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 0,
            hasAppWindow = false,
            msSinceLastTouch = -1L
        )
        assertFalse(sig3.isMeasuredImeWindow)
    }

    @Test
    fun testNoStaleTouchTimerKeepsKeyboardOpen() {
        // User tapped input box 2.5 seconds ago but keyboard window is closed
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = false,
            typeInputMethodHeightPx = 0,
            typeInputMethodBottomPx = 0,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = false,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 0,
            hasAppWindow = false,
            msSinceLastTouch = 2500L
        )

        assertFalse("Closed keyboard must NOT be kept open by a touch timer!", snapshot.isKeyboardVisible)
    }

    @Test
    fun testSecurityExclusionsOverrideAllSignals() {
        // Banking app
        val bankingSnap = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = true,
            isPasswordField = false,
            hasTypeInputMethod = true,
            typeInputMethodHeightPx = 600,
            typeInputMethodBottomPx = 2400,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = true,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 600,
            hasAppWindow = true,
            msSinceLastTouch = 1000L
        )
        assertFalse(bankingSnap.isKeyboardVisible)
        assertTrue(bankingSnap.isRestrictedFieldOrApp)
        assertEquals("RESTRICTED_PKG", bankingSnap.matchedSignal)

        // Password field
        val passwordSnap = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = true,
            hasTypeInputMethod = true,
            typeInputMethodHeightPx = 600,
            typeInputMethodBottomPx = 2400,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = true,
            hasActiveEditableFocus = true,
            appWindowBottomGapPx = 600,
            hasAppWindow = true,
            msSinceLastTouch = 1000L
        )
        assertFalse(passwordSnap.isKeyboardVisible)
        assertTrue(passwordSnap.isRestrictedFieldOrApp)
        assertEquals("PASSWORD_FIELD", passwordSnap.matchedSignal)
    }

    @Test
    fun testNoSignalsMatched() {
        val snapshot = KeyboardVisibilityDetector.evaluateSignals(
            isBankingOrSensitive = false,
            isPasswordField = false,
            hasTypeInputMethod = false,
            typeInputMethodHeightPx = 0,
            typeInputMethodBottomPx = 0,
            screenHeightPx = 2400,
            density = 2.0f,
            hasKnownKeyboardPackage = false,
            knownKeyboardHeightPx = 0,
            isImeInputStarted = false,
            hasActiveEditableFocus = false,
            appWindowBottomGapPx = 0,
            hasAppWindow = false,
            msSinceLastTouch = -1L
        )

        assertFalse(snapshot.isKeyboardVisible)
        assertEquals("NONE", snapshot.matchedSignal)
        assertEquals(0, snapshot.keyboardHeightPx)
    }

    @Test
    fun testBubbleVisibilityRules() {
        // Master switch disabled -> always false
        assertFalse(
            KeyboardVisibilityDetector.shouldBubbleBeVisible(
                isMasterSwitchEnabled = false,
                isRecordingOrProcessing = false,
                isDismissedForCurrentSession = false,
                showOnlyWhenTyping = true,
                isKeyboardVisible = true
            )
        )

        // Actively recording or processing -> always true even if keyboard closed
        assertTrue(
            KeyboardVisibilityDetector.shouldBubbleBeVisible(
                isMasterSwitchEnabled = true,
                isRecordingOrProcessing = true,
                isDismissedForCurrentSession = false,
                showOnlyWhenTyping = true,
                isKeyboardVisible = false
            )
        )

        // Dismissed for current session -> hidden even if keyboard is open
        assertFalse(
            KeyboardVisibilityDetector.shouldBubbleBeVisible(
                isMasterSwitchEnabled = true,
                isRecordingOrProcessing = false,
                isDismissedForCurrentSession = true,
                showOnlyWhenTyping = true,
                isKeyboardVisible = true
            )
        )

        // Show only when typing = true:
        // Keyboard visible -> shows
        assertTrue(
            KeyboardVisibilityDetector.shouldBubbleBeVisible(
                isMasterSwitchEnabled = true,
                isRecordingOrProcessing = false,
                isDismissedForCurrentSession = false,
                showOnlyWhenTyping = true,
                isKeyboardVisible = true
            )
        )
        // Keyboard hidden -> hides
        assertFalse(
            KeyboardVisibilityDetector.shouldBubbleBeVisible(
                isMasterSwitchEnabled = true,
                isRecordingOrProcessing = false,
                isDismissedForCurrentSession = false,
                showOnlyWhenTyping = true,
                isKeyboardVisible = false
            )
        )

        // Show only when typing = false (always float):
        assertTrue(
            KeyboardVisibilityDetector.shouldBubbleBeVisible(
                isMasterSwitchEnabled = true,
                isRecordingOrProcessing = false,
                isDismissedForCurrentSession = false,
                showOnlyWhenTyping = false,
                isKeyboardVisible = false
            )
        )
    }

    @Test
    fun testSessionDismissResetOnKeyboardClose() {
        // Keyboard closes: was visible (true) -> now hidden (false) -> MUST RESET!
        assertTrue(
            KeyboardVisibilityDetector.shouldResetDismissOnKeyboardStateChange(
                wasKeyboardVisible = true,
                isKeyboardVisible = false
            )
        )

        // Keyboard stays visible -> do not reset
        assertFalse(
            KeyboardVisibilityDetector.shouldResetDismissOnKeyboardStateChange(
                wasKeyboardVisible = true,
                isKeyboardVisible = true
            )
        )

        // Keyboard stays closed -> do not reset
        assertFalse(
            KeyboardVisibilityDetector.shouldResetDismissOnKeyboardStateChange(
                wasKeyboardVisible = false,
                isKeyboardVisible = false
            )
        )

        // Keyboard opens (was closed -> now open) -> do not reset
        assertFalse(
            KeyboardVisibilityDetector.shouldResetDismissOnKeyboardStateChange(
                wasKeyboardVisible = false,
                isKeyboardVisible = true
            )
        )
    }
}
