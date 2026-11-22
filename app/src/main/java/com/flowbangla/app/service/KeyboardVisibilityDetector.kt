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
    val isMeasuredImeWindow: Boolean = false,
    val isPhysicalKeyboardOnScreen: Boolean = false,
    val toolbarRowTopY: Int = keyboardTopY
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
        msSinceLastTouch: Long,
        savedImeTopY: Int = (screenHeightPx * 0.60f).toInt()
    ): KeyboardStateSnapshot {
        val minKeyboardHeightPx = (110f * density).toInt()

        // 0. Security Exclusion: Banking apps & password fields
        if (isBankingOrSensitive) {
            return KeyboardStateSnapshot(false, screenHeightPx, 0, true, "RESTRICTED_PKG", false, false)
        }
        if (isPasswordField) {
            return KeyboardStateSnapshot(false, screenHeightPx, 0, true, "PASSWORD_FIELD", false, false)
        }

        // SIGNAL 1: Explicit TYPE_INPUT_METHOD window with height >= 110dp
        if (hasTypeInputMethod && typeInputMethodHeightPx >= minKeyboardHeightPx && typeInputMethodBottomPx >= (screenHeightPx * 0.55f).toInt()) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = screenHeightPx - typeInputMethodHeightPx,
                keyboardHeightPx = typeInputMethodHeightPx,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_1_TYPE_INPUT_METHOD",
                isMeasuredImeWindow = true,
                isPhysicalKeyboardOnScreen = true
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
                isMeasuredImeWindow = true,
                isPhysicalKeyboardOnScreen = true
            )
        }

        // SIGNAL 3: Application Window Bottom Compression (Active window pushed up >= 140dp)
        // Works even when Xiaomi HyperOS or One UI omits TYPE_INPUT_METHOD from getWindows()!
        // isPhysicalKeyboardOnScreen MUST be false so inline strip overlay never shows on non-keyboard dialogs!
        if (hasAppWindow && appWindowBottomGapPx >= (140f * density).toInt()) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = screenHeightPx - appWindowBottomGapPx,
                keyboardHeightPx = appWindowBottomGapPx,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_3_WINDOW_BOTTOM_COMPRESSED",
                isMeasuredImeWindow = true,
                isPhysicalKeyboardOnScreen = false
            )
        }

        // SIGNAL 4: Android 13+ onStartInput/InputConnection active with focused text box
        // isPhysicalKeyboardOnScreen MUST be false when no verified physical keyboard window is present!
        if (isImeInputStarted) {
            val isCompressed = hasAppWindow && appWindowBottomGapPx >= (110f * density).toInt()
            val effectiveTopY = if (isCompressed) screenHeightPx - appWindowBottomGapPx else savedImeTopY
            val effectiveHeight = screenHeightPx - effectiveTopY
            return KeyboardStateSnapshot(
                isKeyboardVisible = true,
                keyboardTopY = effectiveTopY,
                keyboardHeightPx = effectiveHeight,
                isRestrictedFieldOrApp = false,
                matchedSignal = "SIGNAL_4_IME_INPUT_STARTED",
                isMeasuredImeWindow = isCompressed,
                isPhysicalKeyboardOnScreen = false
            )
        }

        return KeyboardStateSnapshot(
            isKeyboardVisible = false,
            keyboardTopY = screenHeightPx,
            keyboardHeightPx = 0,
            isRestrictedFieldOrApp = false,
            matchedSignal = "NONE",
            isMeasuredImeWindow = false,
            isPhysicalKeyboardOnScreen = false
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
        val minKeyboardHeightPx = (115f * density).toInt()

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

        var matchedImeWindow: AccessibilityWindowInfo? = null
        val matchedImeRect = Rect()

        val rect = Rect()
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                window.getBoundsInScreen(rect)
                val h = rect.height()
                if (h >= minKeyboardHeightPx &&
                    rect.top >= (screenHeight * 0.30f).toInt() &&
                    rect.bottom >= (screenHeight * 0.70f).toInt()
                ) {
                    hasTypeInputMethod = true
                    typeInputMethodHeight = h
                    typeInputMethodBottom = rect.bottom
                    matchedImeWindow = window
                    matchedImeRect.set(rect)
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
                    if (h >= minKeyboardHeightPx &&
                        rect.top >= (screenHeight * 0.30f).toInt() &&
                        rect.bottom >= (screenHeight * 0.70f).toInt()
                    ) {
                        hasKnownKeyboard = true
                        knownKeyboardHeight = h
                        matchedImeWindow = window
                        matchedImeRect.set(rect)
                        break
                    }
                }
            }
        }

        // When inside Flow's own activities/dialogs (e.g. Diagnostics modal) and Gboard is not up, keyboard is CLOSED!
        if (activePkg == context.packageName && !hasTypeInputMethod && !hasKnownKeyboard) {
            return KeyboardStateSnapshot(
                isKeyboardVisible = false,
                keyboardTopY = screenHeight,
                keyboardHeightPx = 0,
                isRestrictedFieldOrApp = false,
                matchedSignal = "NONE_FLOW_APP_NO_IME",
                isMeasuredImeWindow = false,
                isPhysicalKeyboardOnScreen = false
            )
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

        val imePrefs = context.getSharedPreferences("flow_ime_geometry", Context.MODE_PRIVATE)
        val savedImeTopY = imePrefs.getInt("verified_ime_top_y", (screenHeight * 0.60f).toInt())

        val snapshot = evaluateSignals(
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
            msSinceLastTouch = msSinceTouch,
            savedImeTopY = savedImeTopY
        )

        val toolbarRowTopY = if (matchedImeWindow != null && snapshot.isPhysicalKeyboardOnScreen) {
            computeToolbarRowTopY(context, matchedImeWindow, matchedImeRect, density)
        } else {
            snapshot.keyboardTopY
        }

        val finalSnapshot = snapshot.copy(toolbarRowTopY = toolbarRowTopY)

        if (finalSnapshot.isPhysicalKeyboardOnScreen && finalSnapshot.isMeasuredImeWindow &&
            finalSnapshot.keyboardTopY in (screenHeight / 4)..(screenHeight - minKeyboardHeightPx / 2)
        ) {
            imePrefs.edit().putInt("verified_ime_top_y", finalSnapshot.keyboardTopY).apply()
            try {
                com.flowbangla.app.FlowBanglaApp.instance.preferencesManager.verifiedImeTopYPortrait = finalSnapshot.keyboardTopY
            } catch (_: Throwable) {}
        }

        return finalSnapshot
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

    fun calculateEmojiBarOffsetPx(currentH: Int, baselineH: Int, density: Float): Int {
        val deltaPx = currentH - baselineH
        return if (deltaPx in (28f * density).toInt()..(64f * density).toInt()) {
            deltaPx
        } else {
            0
        }
    }

    fun computeBottomUpToolbarTop(
        imeBottom: Int,
        imeTop: Int,
        stdBodyHeight: Int,
        minValidHeight: Int
    ): Int {
        return (imeBottom - stdBodyHeight).coerceIn(imeTop, imeBottom - minValidHeight)
    }

    fun findExactToolbarTopFromImeTree(
        root: AccessibilityNodeInfo,
        imeRect: Rect,
        density: Float,
        maxNodes: Int = 45
    ): Int? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val r = Rect()
        var numberOneKeyTop: Int? = null

        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++

            val viewId = try { node.viewIdResourceName.orEmpty() } catch (_: Throwable) { "" }
            val desc = try { node.contentDescription?.toString().orEmpty() } catch (_: Throwable) { "" }
            val text = try { node.text?.toString().orEmpty() } catch (_: Throwable) { "" }

            // 1. Exact match: Gboard's built-in Voice Typing button or Features Menu on the right end of the toolbar row!
            if (viewId.contains("key_pos_header_voice", ignoreCase = true) ||
                viewId.contains("key_pos_header_access_points_menu", ignoreCase = true) ||
                desc.equals("Voice typing", ignoreCase = true) ||
                desc.contains("Voice typing", ignoreCase = true) ||
                desc.equals("Open features menu", ignoreCase = true)
            ) {
                try {
                    node.getBoundsInScreen(r)
                    if (r.top >= imeRect.top && r.height() in (20f * density).toInt()..(64f * density).toInt()) {
                        return r.top
                    }
                } catch (_: Throwable) {}
            }

            // 2. Backup exact match: The '1' key in the number row -> toolbar row is ALWAYS 44dp above it!
            if (numberOneKeyTop == null && (text == "1" || text == "১") && node.isClickable) {
                try {
                    node.getBoundsInScreen(r)
                    if (r.top > imeRect.top + (35f * density).toInt()) {
                        numberOneKeyTop = r.top - (44f * density).toInt()
                    }
                } catch (_: Throwable) {}
            }

            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (_: Throwable) {}
            }
        }
        return numberOneKeyTop
    }

    fun findGboardToolbarAnchorNode(
        root: AccessibilityNodeInfo,
        maxNodes: Int = 25
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < maxNodes) {
            val node = queue.removeFirst()
            count++

            val viewId = try { node.viewIdResourceName.orEmpty() } catch (_: Throwable) { "" }
            val desc = try { node.contentDescription?.toString().orEmpty() } catch (_: Throwable) { "" }

            if (viewId.contains("key_pos_header_voice_input", ignoreCase = true) ||
                viewId.contains("key_pos_header_access_points_menu", ignoreCase = true) ||
                desc.contains("Voice typing", ignoreCase = true) ||
                desc.contains("Open features menu", ignoreCase = true)
            ) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null }
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    fun computeToolbarRowTopY(
        context: Context,
        imeWindow: AccessibilityWindowInfo?,
        imeRect: Rect,
        density: Float
    ): Int {
        val prefs = context.getSharedPreferences("flow_ime_geometry", Context.MODE_PRIVATE)
        val currentH = imeRect.height()
        val minValidH = (220f * density).toInt()

        // TIER 1: Direct lookup for Gboard's Voice button OR the '1' / 'Q' top key row
        if (imeWindow != null) {
            try {
                val root = imeWindow.root
                if (root != null) {
                    val anchorY = findExactToolbarTopFromImeTree(root, imeRect, density, maxNodes = 45)
                    if (anchorY != null) {
                        val detectedBodyH = imeRect.bottom - anchorY
                        if (detectedBodyH >= minValidH) {
                            val savedH = prefs.getInt("std_kbd_body_height_px", 0)
                            if (savedH < minValidH || detectedBodyH < savedH) {
                                prefs.edit().putInt("std_kbd_body_height_px", detectedBodyH).apply()
                            }
                        }
                        return anchorY
                    }
                }
            } catch (_: Throwable) {}
        }

        // TIER 2: Bottom-Up Invariant Anchor from imeRect.bottom
        // The distance from imeRect.bottom to the [⊞ ... 🎤] toolbar row NEVER changes,
        // even when Facebook or WhatsApp adds a 44dp Emoji Fast-Access Bar on top!
        val savedBodyH = prefs.getInt("std_kbd_body_height_px", 0)

        val stdBodyH = when {
            savedBodyH < minValidH -> {
                prefs.edit().putInt("std_kbd_body_height_px", currentH).apply()
                currentH
            }
            currentH < savedBodyH && currentH >= minValidH -> {
                // Discovered the true standard keyboard body height (without Emoji bar)
                prefs.edit().putInt("std_kbd_body_height_px", currentH).apply()
                currentH
            }
            else -> savedBodyH
        }

        return computeBottomUpToolbarTop(imeRect.bottom, imeRect.top, stdBodyH, minValidH)
    }
}