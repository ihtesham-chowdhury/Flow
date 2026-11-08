package com.flowbangla.app.service

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class KeyboardStateSnapshot(
    val isKeyboardVisible: Boolean,
    val keyboardTopY: Int,
    val keyboardHeightPx: Int,
    val isRestrictedFieldOrApp: Boolean
)

object KeyboardVisibilityDetector {

    private val BANKING_AND_SENSITIVE_PACKAGES = setOf(
        "com.bKash.customerapp",
        "com.konasl.nagad",
        "com.dbbl.nexus.pay",
        "com.upay.customer",
        "com.ibbl.cellfin",
        "com.bitwarden.mobile",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.google.android.apps.walletnfcrel"
    )

    private val KNOWN_KEYBOARD_PACKAGES = setOf(
        "com.google.android.inputmethod.latin", // Gboard
        "com.samsung.android.honeyboard",       // Samsung Keyboard
        "net.hasnath.android.keyboard",         // Ridmik Keyboard (popular in Bangladesh!)
        "com.touchtype.swiftkey",               // Microsoft SwiftKey
        "com.mayabi.mayabikeyboard",            // Mayabi Bangla Keyboard
        "com.xiaomi.inputmethod"                // Xiaomi Mint Keyboard
    )

    /**
     * Evaluates whether the soft keyboard is physically visible on screen right now.
     *
     * Signal 1 (Primary): An interactive window of type TYPE_INPUT_METHOD exists
     *                     and has a visible height >= 100dp on screen.
     * Signal 2 (Fallback for OEMs that mask TYPE_INPUT_METHOD): A window belonging to a
     *                     known keyboard package is visible at the bottom of the screen
     *                     WITH an active InputConnection or focused editable node.
     */
    fun evaluateKeyboardState(
        context: Context,
        windows: List<AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo?,
        isImeInputStarted: Boolean
    ): KeyboardStateSnapshot {
        val density = context.resources.displayMetrics.density
        val screenHeight = context.resources.displayMetrics.heightPixels
        val minKeyboardHeightPx = (100f * density).toInt()

        // 1. Check if the foreground app or focused field is a restricted banking app or password field
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (BANKING_AND_SENSITIVE_PACKAGES.any { activePkg.contains(it, ignoreCase = true) }) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = false,
                keyboardTopY = screenHeight,
                keyboardHeightPx = 0,
                isRestrictedFieldOrApp = true
            )
        }

        val focusedNode = try {
            activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (focusedNode?.isPassword == true) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = false,
                keyboardTopY = screenHeight,
                keyboardHeightPx = 0,
                isRestrictedFieldOrApp = true
            )
        }

        // 2. Primary Check: Look for AccessibilityWindowInfo.TYPE_INPUT_METHOD with height >= 100dp
        val rect = Rect()
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                window.getBoundsInScreen(rect)
                val height = rect.height()
                if (height >= minKeyboardHeightPx && rect.bottom >= (screenHeight * 0.65f).toInt()) {
                    return KeyboardStateSnapshot(
                        isKeyboardVisible = true,
                        keyboardTopY = rect.top,
                        keyboardHeightPx = height,
                        isRestrictedFieldOrApp = false
                    )
                }
            }
        }

        // 3. Secondary Fallback: Check if a known keyboard package window is open at the bottom
        for (window in windows) {
            val pkg = try { window.root?.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
            if (pkg in KNOWN_KEYBOARD_PACKAGES || KNOWN_KEYBOARD_PACKAGES.any { pkg.contains(it, ignoreCase = true) }) {
                window.getBoundsInScreen(rect)
                val height = rect.height()
                if (height >= minKeyboardHeightPx && rect.bottom >= (screenHeight * 0.65f).toInt()) {
                    return KeyboardStateSnapshot(
                        isKeyboardVisible = true,
                        keyboardTopY = rect.top,
                        keyboardHeightPx = height,
                        isRestrictedFieldOrApp = false
                    )
                }
            }
        }

        // 4. If no keyboard window is open on screen, the keyboard is CLOSED!
        // (Even if an EditText still holds stale focus in the background!)
        return KeyboardStateSnapshot(
            isKeyboardVisible = false,
            keyboardTopY = screenHeight,
            keyboardHeightPx = 0,
            isRestrictedFieldOrApp = false
        )
    }

    fun isRestrictedPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return BANKING_AND_SENSITIVE_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
    }

    fun isKnownKeyboard(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return KNOWN_KEYBOARD_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
    }

    fun isKeyboardHeightValid(heightPx: Int, density: Float): Boolean {
        val minPx = (100f * density).toInt()
        return heightPx >= minPx
    }

    /**
     * Pure evaluator for bubble visibility state transitions.
     */
    fun shouldBubbleBeVisible(
        isMasterSwitchEnabled: Boolean,
        isRecordingOrProcessing: Boolean,
        isDismissedForCurrentSession: Boolean,
        showOnlyWhenTyping: Boolean,
        isKeyboardVisible: Boolean
    ): Boolean {
        if (!isMasterSwitchEnabled) return false
        if (isRecordingOrProcessing) return true
        if (isDismissedForCurrentSession) return false
        return if (showOnlyWhenTyping) isKeyboardVisible else true
    }

    /**
     * Determines whether the per-session dismiss flag should reset on keyboard state transition.
     * When the user closes the keyboard (transition from visible -> hidden), the session dismiss
     * MUST reset so the mic re-appears on the next keyboard opening!
     */
    fun shouldResetDismissOnKeyboardStateChange(
        wasKeyboardVisible: Boolean,
        isKeyboardVisible: Boolean
    ): Boolean {
        return wasKeyboardVisible && !isKeyboardVisible
    }
}
