package com.flowbangla.app.service

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class KeyboardStateSnapshot(
    val isKeyboardVisible: Boolean,
    val keyboardTopY: Int,
    val keyboardHeightPx: Int,
    val isRestrictedFieldOrApp: Boolean,
    val matchedSignal: String = "NONE",
    val isMeasuredImeWindow: Boolean = false
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
        "net.hasnath.android.keyboard",         // Ridmik Keyboard
        "com.touchtype.swiftkey",               // Microsoft SwiftKey
        "com.mayabi.mayabikeyboard",            // Mayabi Bangla Keyboard
        "com.xiaomi.inputmethod",               // Xiaomi Mint Keyboard
        "com.baidu.input_mi"                    // Xiaomi MIUI Keyboard
    )

    /**
     * Pure evaluator testing the 5 independent keyboard signals.
     */
    fun evaluateSignals(
        isBankingOrSensitive: Boolean,
        isPasswordField: Boolean,
        hasTypeInputMethod: Boolean,
        typeInputMethodHeightPx: Int,
        typeInputMethodBottomPx: Int,
        screenHeightPx: Int,
        density: Float,
        hasKnownKeyboardPackage: Boolean,
        knownKeyboardHeightPx: Int,
        isImeInputStarted: Boolean,
        hasActiveEditableFocus: Boolean,
        appWindowBottomGapPx: Int,
        hasAppWindow: Boolean,
        msSinceLastTouch: Long
    ): KeyboardStateSnapshot {
        val minKeyboardHeightPx = (100f * density).toInt()

        // 0. Security Exclusion: Banking apps & password fields
        if (isBankingOrSensitive) {
            return KeyboardStateSnapshot(false, screenHeightPx, 0, true, "RESTRICTED_PKG", false)
        }
        if (isPasswordField) {
            return KeyboardStateSnapshot(false, screenHeightPx, 0, true, "PASSWORD_FIELD", false)
        }

        // SIGNAL 1: Explicit TYPE_INPUT_METHOD window with height >= 100dp
        if (hasTypeInputMethod && typeInputMethodHeightPx >= minKeyboardHeightPx && typeInputMethodBottomPx >= (screenHeightPx * 0.60f).toInt()) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = screenHeightPx - typeInputMethodHeightPx,
                keyboardHeightPx = typeInputMethodHeightPx,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_1_TYPE_INPUT_METHOD",
                isMeasuredImeWindow = true
            )
        }

        // SIGNAL 2: Known Keyboard Package Window
        if (hasKnownKeyboardPackage && knownKeyboardHeightPx >= minKeyboardHeightPx) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = screenHeightPx - knownKeyboardHeightPx,
                keyboardHeightPx = knownKeyboardHeightPx,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_2_KEYBOARD_PKG",
                isMeasuredImeWindow = true
            )
        }

        // SIGNAL 3: Android 13+ AccessibilityInputConnection is actively bound to a real input editor!
        // On Xiaomi HyperOS after a Recents swipe, getWindows() can return empty even though
        // onStartInput() fired with a real EditorInfo!
        if (isImeInputStarted && hasActiveEditableFocus) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = 0, // Unmeasured: overlay must NOT overwrite its verified topY!
                keyboardHeightPx = 0,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_3_IME_INPUT_STARTED",
                isMeasuredImeWindow = false
            )
        }

        // SIGNAL 4: Application Window Bottom Compression (Active window is pushed up by >= 140dp at bottom)
        if (hasAppWindow && hasActiveEditableFocus && appWindowBottomGapPx >= (140f * density).toInt()) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = screenHeightPx - appWindowBottomGapPx,
                keyboardHeightPx = appWindowBottomGapPx,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_4_WINDOW_BOTTOM_COMPRESSED",
                isMeasuredImeWindow = false
            )
        }

        return KeyboardStateSnapshot(
            isKeyboardVisible = false,
            keyboardTopY = screenHeightPx,
            keyboardHeightPx = 0,
            isRestrictedFieldOrApp = false,
            matchedSignal = "NONE",
            isMeasuredImeWindow = false
        )
    }

    fun evaluateKeyboardState(
        context: Context,
        windows: List<AccessibilityWindowInfo>,
        activeRoot: AccessibilityNodeInfo?,
        isImeInputStarted: Boolean,
        hasActiveEditableFocus: Boolean = false,
        lastEditableTouchElapsedMs: Long = 0L
    ): KeyboardStateSnapshot {
        val density = context.resources.displayMetrics.density
        val screenHeight = context.resources.displayMetrics.heightPixels
        val minKeyboardHeightPx = (100f * density).toInt()

        // 0. Security Exclusion: Banking apps & password fields
        val activePkg = try { activeRoot?.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
        val isBanking = BANKING_AND_SENSITIVE_PACKAGES.any { activePkg.contains(it, ignoreCase = true) }

        val focusedNode = try {
            activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Throwable) {
            null
        }
        val isPassword = focusedNode?.isPassword == true

        var hasTypeInputMethod = false
        var typeInputMethodHeight = 0
        var typeInputMethodBottom = 0

        var hasKnownKeyboard = false
        var knownKeyboardHeight = 0

        val rect = Rect()
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                window.getBoundsInScreen(rect)
                val h = rect.height()
                if (h >= minKeyboardHeightPx && rect.bottom >= (screenHeight * 0.60f).toInt()) {
                    hasTypeInputMethod = true
                    typeInputMethodHeight = h
                    typeInputMethodBottom = rect.bottom
                    break
                }
            }
        }

        if (!hasTypeInputMethod) {
            for (window in windows) {
                val pkg = try { window.root?.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
                if (pkg in KNOWN_KEYBOARD_PACKAGES || KNOWN_KEYBOARD_PACKAGES.any { pkg.contains(it, ignoreCase = true) }) {
                    window.getBoundsInScreen(rect)
                    val h = rect.height()
                    if (h >= minKeyboardHeightPx) {
                        hasKnownKeyboard = true
                        knownKeyboardHeight = h
                        break
                    }
                }
            }
        }

        var appWindowBottomGap = 0
        var hasAppWindow = false
        if (activeRoot != null) {
            try {
                activeRoot.getBoundsInScreen(rect)
                if (rect.height() > minKeyboardHeightPx) {
                    hasAppWindow = true
                    appWindowBottomGap = screenHeight - rect.bottom
                }
            } catch (_: Throwable) {}
        }

        val msSinceTouch = if (lastEditableTouchElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - lastEditableTouchElapsedMs
        } else {
            -1L
        }

        return evaluateSignals(
            isBankingOrSensitive = isBanking,
            isPasswordField = isPassword,
            hasTypeInputMethod = hasTypeInputMethod,
            typeInputMethodHeightPx = typeInputMethodHeight,
            typeInputMethodBottomPx = typeInputMethodBottom,
            screenHeightPx = screenHeight,
            density = density,
            hasKnownKeyboardPackage = hasKnownKeyboard,
            knownKeyboardHeightPx = knownKeyboardHeight,
            isImeInputStarted = isImeInputStarted,
            hasActiveEditableFocus = hasActiveEditableFocus,
            appWindowBottomGapPx = appWindowBottomGap,
            hasAppWindow = hasAppWindow,
            msSinceLastTouch = msSinceTouch
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
