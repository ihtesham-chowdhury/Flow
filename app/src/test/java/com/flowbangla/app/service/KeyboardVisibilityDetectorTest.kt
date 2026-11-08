package com.flowbangla.app.service

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
