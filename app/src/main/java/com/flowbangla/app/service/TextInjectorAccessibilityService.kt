package com.flowbangla.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.flowbangla.app.data.PreferencesManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import com.flowbangla.app.FlowBanglaApp
import com.flowbangla.app.format.ActiveAppContextExtractor
import com.flowbangla.app.format.ActiveFieldContext
import com.flowbangla.app.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.coroutines.resume

enum class InjectionStatus {
    INJECTED_PASTE,
    INJECTED_SET_TEXT,
    DEFERRED_WAITING_FOR_TAP, // Clipboard loaded; will auto-paste when user taps the text box
    FAILED
}

data class InjectionOutcome(
    val status: InjectionStatus,
    val targetPackage: String?,
    val detail: String
)

class TextInjectorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowA11yInjector"

        @Volatile
        var instance: TextInjectorAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected

        /**
         * Pending text that could not be immediately pasted because the host app (e.g. YouTube/Facebook)
         * lost focus while the user was speaking. When non-null, tapping ANY editable field within
         * 45 seconds will automatically paste it!
         */
        @Volatile
        var pendingDeferredText: String? = null
        @Volatile
        var pendingDeferredTimestampMs: Long = 0L
        private const val DEFERRED_PASTE_TTL_MS = 45_000L

        fun isEnabled(context: Context): Boolean {
            return PermissionHelper.isAccessibilityServiceEnabled(context)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    var onKeyboardVisibilityChanged: ((isKeyboardVisible: Boolean) -> Unit)? = null

    @Volatile
    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null

    @Volatile
    private var lastKnownPackageName: String? = null

    @Volatile
    private var lastPastedText: String? = null
    @Volatile
    private var lastPastedTimestampMs: Long = 0L
    private val AUTO_LEARN_WINDOW_MS = 15_000L

    private var isBubbleServiceBound = false

    private val bubbleServiceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            isBubbleServiceBound = true
            syncKeyboardVisibilityWithBubble()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBubbleServiceBound = false
            ensureBubbleServiceAliveAndBound()
        }
    }

    fun ensureBubbleServiceAliveAndBound() {
        try {
            val prefs = PreferencesManager(this)
            if (!prefs.isMasterSwitchEnabled) return

            val intent = Intent(this, FloatingBubbleService::class.java)
            if (FloatingBubbleService.instance == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            if (!isBubbleServiceBound) {
                bindService(
                    intent,
                    bubbleServiceConnection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog re-bind warning: ${e.message}")
        }
    }

    fun ensureInteractiveWindowsFlagRegistered() {
        try {
            val info = serviceInfo ?: return
            val requiredFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                } else 0)

            if ((info.flags and requiredFlags) != requiredFlags) {
                info.flags = info.flags or requiredFlags
                serviceInfo = info
                Log.i(TAG, "Re-asserted interactive windows flags for OEM survival.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to re-assert flags: ${e.message}")
        }
    }

    private var keyboardStripController: KeyboardStripOverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true

        ensureInteractiveWindowsFlagRegistered()
        Log.i(TAG, "TextInjectorAccessibilityService connected with multi-window flags.")

        // Host the unkillable Bitwarden-style inline keyboard chip directly inside this AccessibilityService
        val prefs = PreferencesManager(this)
        keyboardStripController = KeyboardStripOverlayController(
            a11yService = this,
            prefs = prefs,
            onStartRecording = {
                FloatingBubbleService.instance?.startRecordingFromExternal()
            },
            onConfirmRecording = {
                FloatingBubbleService.instance?.onUserConfirmDictation()
            },
            onCancelRecording = {
                FloatingBubbleService.instance?.onUserCancelDictation()
            },
            onToggleLanguage = {
                FloatingBubbleService.instance?.toggleLanguage()
            }
        )

        // Bind FloatingBubbleService with BIND_IMPORTANT so it shares AccessibilityService's unkillable priority
        ensureBubbleServiceAliveAndBound()
        syncKeyboardVisibilityWithBubble()
    }

    // Track live IME session state on Android 13+ (API 33+)
    @Volatile
    private var a11yInputMethod: InputMethod? = null

    @Volatile
    private var isInputConnectionStarted: Boolean = false

    @Volatile
    private var currentEditorPackage: String? = null

    @Volatile
    private var lastEditableTouchElapsedMs: Long = 0L

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod {
        val im = object : InputMethod(this) {
            override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
                super.onStartInput(attribute, restarting)
                val isRealInput = attribute.inputType != android.text.InputType.TYPE_NULL
                isInputConnectionStarted = isRealInput
                currentEditorPackage = attribute.packageName
                if (isRealInput) {
                    lastEditableTouchElapsedMs = SystemClock.elapsedRealtime()
                }
                syncKeyboardVisibilityWithBubble()
                // If we have pending deferred text waiting for a text box to focus, commit it immediately!
                val deferred = pendingDeferredText
                if (!deferred.isNullOrBlank()) {
                    val age = SystemClock.elapsedRealtime() - pendingDeferredTimestampMs
                    if (age <= DEFERRED_PASTE_TTL_MS) {
                        pendingDeferredText = null
                        serviceScope.launch {
                            delay(50L)
                            commitViaAccessibilityIme(deferred)
                            FloatingBubbleService.instance?.hidePendingPasteBanner()
                        }
                    }
                }
            }

            override fun onFinishInput() {
                super.onFinishInput()
                isInputConnectionStarted = false
                lastEditableTouchElapsedMs = 0L
                syncKeyboardVisibilityWithBubble()
            }
        }
        a11yInputMethod = im
        return im
    }

    /**
     * TIER 0 & TIER 1: Direct OS InputConnection commitText() & paste (API 33+).
     * Works 100% of the time in YouTube Shorts, Facebook Litho views, WebViews, Flutter, and games
     * whenever the soft keyboard is active, because it writes directly to the IME InputConnection!
     */
    private fun commitViaAccessibilityIme(nfcText: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val im = a11yInputMethod ?: getInputMethod() ?: return false
            val ic = im.currentInputConnection ?: return false

            // Check surrounding text via IME to see if we need a leading space
            val surrounding = ic.getSurroundingText(2, 0, 0)
            val beforeChar = surrounding?.text?.lastOrNull()
            val finalToCommit = if (beforeChar != null && !beforeChar.isWhitespace() && !nfcText.first().isWhitespace()) {
                " $nfcText"
            } else {
                nfcText
            }

            // 1. Primary: Direct commitText (1 = place cursor at end of inserted text)
            ic.commitText(finalToCommit, 1, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "AccessibilityInputConnection commitText failed: ${e.message}")
            false
        }
    }

    /**
     * Reads up to [maxChars] before the cursor directly from the OS InputConnection on API 33+,
     * falling back to AccessibilityNodeInfo when on API 26..32.
     * Never accidentally reads placeholder hint text!
     */
    fun getLiveTextBeforeCursor(maxChars: Int = 120): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val ic = (a11yInputMethod ?: getInputMethod())?.currentInputConnection
                if (ic != null) {
                    val surrounding = ic.getSurroundingText(maxChars, 0, 0)
                    if (surrounding != null) {
                        return surrounding.text.toString()
                    }
                }
            } catch (_: Exception) {}
        }
        return ""
    }

    /**
     * TIER 4: Physical Coordinate Wake-Up Tap via dispatchGesture().
     * Used when YouTube Shorts or Facebook shows a comment input box on screen,
     * but its InputConnection is not yet focused/bound.
     */
    private suspend fun dispatchPhysicalTapToWakeInput(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() < 20 || rect.height() < 20) return false

        // Tap slightly left of center so we hit the text area and never hit a trailing "Send" or "Emoji" icon
        val tapX = (rect.left + rect.width() * 0.35f).coerceIn(rect.left + 10f, rect.right - 10f)
        val tapY = rect.exactCenterY()

        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 18L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    private fun getActualNonHintText(node: AccessibilityNodeInfo): String {
        val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.isShowingHintText else false
        if (isHint) return ""
        val raw = node.text?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        return if (hint.isNotEmpty() && raw == hint) "" else raw
    }

    private var lastWindowSyncTimeMs = 0L
    private var windowSyncJob: kotlinx.coroutines.Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()

        // 1. NEVER process accessibility events triggered by Flow's own overlay view!
        // This prevents the infinite feedback loop when the Ochre spinner rotates!
        if (pkg == packageName) return

        if (pkg.isNotBlank() && pkg != "com.android.systemui") {
            lastKnownPackageName = pkg
        }

        // 2. Auto-revive: If FloatingBubbleService was killed by OEM, revive it on the very next user event!
        if (FloatingBubbleService.instance == null) {
            ensureBubbleServiceAliveAndBound()
        }

        // 3. Cache focused/clicked/selection input node in O(1) without deep tree traversal
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            val src = event.source
            if (src != null && isCandidateInputNode(src)) {
                cacheEditableNode(src)
                lastEditableTouchElapsedMs = SystemClock.elapsedRealtime()
                checkAndExecuteDeferredPaste(src)
                syncKeyboardVisibilityWithBubble()
            }
        }

        // 3. Debounce keyboard visibility checks (at most once every 220ms)
        // and NEVER run getWindows() while the bubble is actively RECORDING or PROCESSING!
        val bubbleState = FloatingBubbleService.instance?.currentState
        if (bubbleState is com.flowbangla.app.model.TranscriptionState.Recording ||
            bubbleState is com.flowbangla.app.model.TranscriptionState.Processing
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowSyncTimeMs >= 220L) {
            lastWindowSyncTimeMs = now
            windowSyncJob?.cancel()
            windowSyncJob = serviceScope.launch(Dispatchers.Main) {
                delay(80L)
                syncKeyboardVisibilityWithBubble()
            }
        }

        // Post-Paste 15-second Auto-Learn Watcher
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val pasted = lastPastedText
            val elapsed = SystemClock.elapsedRealtime() - lastPastedTimestampMs
            if (pasted != null && elapsed <= AUTO_LEARN_WINDOW_MS) {
                val currentText = event.text?.joinToString("") ?: event.source?.text?.toString() ?: ""
                if (currentText.isNotBlank() && currentText != pasted) {
                    try {
                        val learned = FlowBanglaApp.instance.personalDictionaryManager.learnFromUserCorrection(
                            pastedText = pasted,
                            editedText = currentText
                        )
                        if (learned != null) {
                            Log.i(TAG, "✨ Auto-learned correction: '${learned.misspelling}' -> '${learned.word}'")
                            lastPastedText = currentText
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-learn check error: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * If a dictation finished while no input box was focused (or YouTube closed its decoy box),
     * the user just needs to tap the comment box and Flow immediately pastes the pending text!
     */
    private fun checkAndExecuteDeferredPaste(targetNode: AccessibilityNodeInfo) {
        val deferred = pendingDeferredText ?: return
        val ageMs = SystemClock.elapsedRealtime() - pendingDeferredTimestampMs
        if (ageMs > DEFERRED_PASTE_TTL_MS) {
            pendingDeferredText = null
            return
        }

        pendingDeferredText = null
        serviceScope.launch {
            delay(80L) // Let YouTube/Facebook finish mounting the bottom-sheet input editor
            injectTextSuspend(deferred, isDeferredRetry = true)
            FloatingBubbleService.instance?.hidePendingPasteBanner()
        }
    }

    private fun cacheEditableNode(node: AccessibilityNodeInfo) {
        if (node.packageName?.toString() == packageName) return
        lastFocusedEditableNode = node
    }

    /**
     * Recognizes standard EditTexts, custom Litho / React Native / WebView comment inputs,
     * and bottom-sheet comment composers used by Facebook, Messenger, YouTube, Instagram, and Chrome.
     */
    fun isCandidateInputNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isVisibleToUser || !node.isEnabled) return false
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.android.systemui") return false

        if (node.isEditable) return true

        val actions = node.actionList.map { it.id }
        val supportsSetTextOrPaste = actions.contains(AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            actions.contains(AccessibilityNodeInfo.ACTION_PASTE)

        val className = node.className?.toString().orEmpty()
        val isKnownInputClass = className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoComplete", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)

        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val isCommentOrComposerId = viewId.contains("comment") || viewId.contains("composer") ||
            viewId.contains("input") || viewId.contains("reply") || viewId.contains("message") ||
            viewId.contains("text")

        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val isCommentDesc = desc.contains("comment") || desc.contains("reply") || desc.contains("মন্তব্য")

        if (supportsSetTextOrPaste && (node.isFocused || node.isFocusable || isKnownInputClass || isCommentOrComposerId || isCommentDesc)) {
            return true
        }

        if (isKnownInputClass && (node.isFocusable || node.isFocused)) {
            return true
        }

        return false
    }

    /**
     * 5-Step Live Target Node Resolver:
     * Searches active window focus, topmost dialog windows in descending layer order (catches YouTube & Facebook
     * comment bottom-sheets), refreshed cached node, and deep DFS traversal across application windows.
     */
    fun findBestTargetNode(allowCachedFallback: Boolean = true): AccessibilityNodeInfo? {
        // Step 1: Direct FOCUS_INPUT on rootInActiveWindow
        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            val focusedInput = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (isCandidateInputNode(focusedInput)) {
                focusedInput?.refresh()
                return focusedInput
            }
        }

        // Step 2: Search across ALL interactive windows sorted by layer descending (topmost dialogs/bottom-sheets first)
        val windowList: List<AccessibilityWindowInfo> = try {
            windows.sortedByDescending { it.layer }
        } catch (_: Exception) {
            emptyList()
        }

        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            ) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (isCandidateInputNode(focused)) {
                    focused?.refresh()
                    return focused
                }
            }
        }

        // Step 3: Verify & Refresh cached node
        if (allowCachedFallback) {
            val cached = lastFocusedEditableNode
            if (cached != null) {
                val refreshed = try { cached.refresh() } catch (_: Exception) { false }
                if (refreshed && isCandidateInputNode(cached)) {
                    return cached
                }
            }
        }

        // Step 4: Search topmost application windows with requireFocus = true
        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val dfsFocused = findEditableByDfs(root, requireFocus = true)
            if (dfsFocused != null) return dfsFocused
        }

        // Step 5: Search topmost application windows with requireFocus = false (Crucial for YouTube/FB bottom-sheet comment inputs)
        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val dfsAny = findEditableByDfs(root, requireFocus = false)
            if (dfsAny != null) return dfsAny
        }

        if (activeRoot != null) {
            val dfsAny = findEditableByDfs(activeRoot, requireFocus = false)
            if (dfsAny != null) return dfsAny
        }

        return null
    }

    private fun findEditableByDfs(
        root: AccessibilityNodeInfo?,
        requireFocus: Boolean,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (root == null || depth > 18) return null
        if (isCandidateInputNode(root) && (!requireFocus || root.isFocused)) {
            root.refresh()
            return root
        }
        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableByDfs(child, requireFocus, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * Master Suspending Injection Pipeline:
     * Executes Clipboard Pre-load -> Tier 0 (API 33+ Direct OS IME commitText) -> Tier 1 (Verified ACTION_PASTE)
     * -> Tier 2 (Focus + IME / Verified Paste) -> Tier 3 (Hint-Safe ACTION_SET_TEXT)
     * -> Tier 4 (Physical Gesture Tap Wake-Up) -> Tier 5 (Deferred 1-Tap Auto-Paste Recovery).
     */
    suspend fun injectTextSuspend(
        rawFormattedText: String,
        isDeferredRetry: Boolean = false
    ): InjectionOutcome = withContext(Dispatchers.Main) {
        if (rawFormattedText.isEmpty()) {
            return@withContext InjectionOutcome(InjectionStatus.FAILED, lastKnownPackageName, "Empty text")
        }

        // Always normalize to Unicode NFC so Bengali matras (ে, ৈ, ো, ৌ) and hasanta (্) never split
        val formattedText = Normalizer.normalize(rawFormattedText, Normalizer.Form.NFC)

        // Step 0: Pre-load system clipboard from privileged Accessibility context
        copyToClipboardPrivileged(formattedText)
        delay(35L)

        // TIER 0: Direct Android 13+ (API 33+) AccessibilityInputConnection.commitText()
        // This is the #1 reason Wispr Flow never fails on Android 13+: it writes straight into the IME pipe!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (commitViaAccessibilityIme(formattedText)) {
                pendingDeferredText = null
                lastPastedText = formattedText
                lastPastedTimestampMs = SystemClock.elapsedRealtime()
                return@withContext InjectionOutcome(
                    status = InjectionStatus.INJECTED_PASTE,
                    targetPackage = currentEditorPackage ?: lastKnownPackageName,
                    detail = "Tier 0 AccessibilityInputConnection.commitText succeeded"
                )
            }
        }

        // Locate candidate target node across all interactive windows (sorted by top layer)
        var targetNode = findBestTargetNode(allowCachedFallback = true)

        // TIER 1: If we found a YouTube Shorts / Facebook comment node on screen, wake it up!
        if (targetNode != null) {
            val pkg = targetNode.packageName?.toString() ?: lastKnownPackageName
            targetNode.refresh()

            // Snapshot text before attempting node paste so we can verify if the paste actually took effect
            val textBefore = getActualNonHintText(targetNode)

            if (targetNode.isFocused) {
                val pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                delay(70L)
                targetNode.refresh()
                val textAfter = getActualNonHintText(targetNode)
                if (pasted && (textAfter != textBefore || textAfter.contains(formattedText.take(6)))) {
                    pendingDeferredText = null
                    lastPastedText = formattedText
                    lastPastedTimestampMs = SystemClock.elapsedRealtime()
                    return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 1 Verified ACTION_PASTE")
                }
            }

            // TIER 2: Request accessibility focus + click, then try both IME commitText AND verified ACTION_PASTE
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(90L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && commitViaAccessibilityIme(formattedText)) {
                pendingDeferredText = null
                lastPastedText = formattedText
                lastPastedTimestampMs = SystemClock.elapsedRealtime()
                return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 2 Focus + IME commitText")
            }

            targetNode = findBestTargetNode(allowCachedFallback = true) ?: targetNode
            targetNode.refresh()
            val beforeTier2 = getActualNonHintText(targetNode)
            val pastedAfterFocus = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(70L)
            targetNode.refresh()
            val afterTier2 = getActualNonHintText(targetNode)

            if (pastedAfterFocus && (afterTier2 != beforeTier2 || afterTier2.contains(formattedText.take(6)))) {
                pendingDeferredText = null
                lastPastedText = formattedText
                lastPastedTimestampMs = SystemClock.elapsedRealtime()
                return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 2 Verified Focus + ACTION_PASTE")
            }

            // TIER 3: Hint-Safe Cursor-Spliced ACTION_SET_TEXT
            if (performHintSafeSetText(targetNode, formattedText)) {
                delay(60L)
                targetNode.refresh()
                if (getActualNonHintText(targetNode).isNotEmpty()) {
                    pendingDeferredText = null
                    lastPastedText = formattedText
                    lastPastedTimestampMs = SystemClock.elapsedRealtime()
                    return@withContext InjectionOutcome(InjectionStatus.INJECTED_SET_TEXT, pkg, "Tier 3 Verified ACTION_SET_TEXT")
                }
            }

            // TIER 4: Physical Coordinate Tap Wake-Up (for YouTube Shorts / Facebook decoy comment bars)
            // Dispatches a real 18ms touch tap inside the comment box bounds, waits 180ms for keyboard IME to bind, then commits!
            if (!isDeferredRetry && dispatchPhysicalTapToWakeInput(targetNode)) {
                delay(180L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && commitViaAccessibilityIme(formattedText)) {
                    pendingDeferredText = null
                    lastPastedText = formattedText
                    lastPastedTimestampMs = SystemClock.elapsedRealtime()
                    return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 4 Gesture Wake-Up + IME commitText")
                }
                val wokeNode = findBestTargetNode(allowCachedFallback = false)
                if (wokeNode != null) {
                    if (wokeNode.performAction(AccessibilityNodeInfo.ACTION_PASTE) || performHintSafeSetText(wokeNode, formattedText)) {
                        pendingDeferredText = null
                        lastPastedText = formattedText
                        lastPastedTimestampMs = SystemClock.elapsedRealtime()
                        return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 4 Gesture Wake-Up + Node Paste")
                    }
                }
            }
        }

        // TIER 5: Deferred Auto-Paste (armed for 45s; the instant the user taps any comment box, onStartInput() commits it!)
        if (!isDeferredRetry) {
            armDeferredPaste(formattedText)
        }
        return@withContext InjectionOutcome(
            status = InjectionStatus.DEFERRED_WAITING_FOR_TAP,
            targetPackage = lastKnownPackageName,
            detail = "Armed deferred auto-paste on next input focus."
        )
    }

    /**
     * Safely splices [newText] into [node] using ACTION_SET_TEXT without accidentally
     * duplicating placeholder hint text (e.g., "Add a comment..." / "কমেন্ট লিখুন...").
     */
    private fun performHintSafeSetText(node: AccessibilityNodeInfo, newText: String): Boolean {
        node.refresh()
        val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.isShowingHintText
        } else {
            false
        }
        val rawExisting = node.text?.toString().orEmpty()
        val hintString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }

        // If the node is showing hint text, treat existing content as empty!
        val actualExisting = com.flowbangla.app.util.TextSpliceHelper.resolveEffectiveText(rawExisting, isHint, hintString)
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd

        val spliceResult = com.flowbangla.app.util.TextSpliceHelper.spliceText(actualExisting, newText, selStart, selEnd)
        val combinedText = spliceResult.combinedText
        val newCursorPos = spliceResult.newCursorPos

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combinedText)
        }
        val didSet = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (didSet) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }
        return didSet
    }

    private fun copyToClipboardPrivileged(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("FlowDictation", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard pre-load failed", e)
        }
    }

    private fun armDeferredPaste(text: String) {
        pendingDeferredText = text
        pendingDeferredTimestampMs = SystemClock.elapsedRealtime()
        FloatingBubbleService.instance?.showPendingPasteBanner(text)
    }

    /**
     * Fast, non-blocking injection that completes in < 150ms and NEVER deadlocks the Main Thread.
     */
    suspend fun injectTextFastAndSafe(rawFormattedText: String): Boolean = withContext(Dispatchers.Main) {
        if (rawFormattedText.isEmpty()) return@withContext false
        val formattedText = Normalizer.normalize(rawFormattedText, Normalizer.Form.NFC)

        // Always pre-load clipboard first
        copyToClipboardPrivileged(formattedText)

        // TIER 0: Android 13+ (API 33+) Direct AccessibilityInputConnection.commitText()
        // Zero node traversal needed — writes straight to the active keyboard connection in < 5ms!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (commitViaAccessibilityIme(formattedText)) {
                pendingDeferredText = null
                lastPastedText = formattedText
                lastPastedTimestampMs = SystemClock.elapsedRealtime()
                return@withContext true
            }
        }

        // TIER 1: O(1) Direct Focus Lookup (rootInActiveWindow.findFocus(FOCUS_INPUT))
        val targetNode = findTargetNodeBounded()
        if (targetNode != null) {
            try {
                targetNode.refresh()
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    pendingDeferredText = null
                    lastPastedText = formattedText
                    lastPastedTimestampMs = SystemClock.elapsedRealtime()
                    return@withContext true
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    pendingDeferredText = null
                    lastPastedText = formattedText
                    lastPastedTimestampMs = SystemClock.elapsedRealtime()
                    return@withContext true
                }
                if (performHintSafeSetText(targetNode, formattedText)) {
                    pendingDeferredText = null
                    lastPastedText = formattedText
                    lastPastedTimestampMs = SystemClock.elapsedRealtime()
                    return@withContext true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Node injection fallback warning: ${e.message}")
            }
        }

        // TIER 2: Arm deferred paste for next tap
        armDeferredPaste(formattedText)
        false
    }

    /**
     * Strictly bounded node finder: checks O(1) FOCUS_INPUT first, then cached node,
     * then a strictly capped 30-node BFS/DFS so it NEVER freezes the UI thread!
     */
    fun findTargetNodeBounded(): AccessibilityNodeInfo? {
        // 1. O(1) Active Window Focus
        val activeRoot = try { rootInActiveWindow } catch (_: Throwable) { null }
        if (activeRoot != null) {
            val focused = try { activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
            if (isCandidateInputNode(focused)) return focused
        }

        // 2. O(1) Cached Node
        val cached = lastFocusedEditableNode
        if (cached != null) {
            val ok = try { cached.refresh() } catch (_: Throwable) { false }
            if (ok && isCandidateInputNode(cached)) return cached
        }

        // 3. O(1) Multi-Window FOCUS_INPUT (no deep traversal)
        val winList = try { windows } catch (_: Throwable) { emptyList() }
        for (w in winList) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val r = try { w.root } catch (_: Throwable) { null } ?: continue
                val f = try { r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
                if (isCandidateInputNode(f)) return f
            }
        }

        // 4. Strictly bounded DFS (max 30 nodes total!)
        if (activeRoot != null) {
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            return findEditableBoundedDfs(activeRoot, counter, maxNodes = 30, depth = 0)
        }
        return null
    }

    private fun findEditableBoundedDfs(
        node: AccessibilityNodeInfo?,
        visitedCounter: java.util.concurrent.atomic.AtomicInteger,
        maxNodes: Int,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 7 || visitedCounter.incrementAndGet() > maxNodes) return null
        if (isCandidateInputNode(node)) return node
        val count = node.childCount
        for (i in 0 until count) {
            if (visitedCounter.get() > maxNodes) break
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val found = findEditableBoundedDfs(child, visitedCounter, maxNodes, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun extractActiveFieldContextFast(): ActiveFieldContext {
        val target = findTargetNodeBounded()
        return ActiveAppContextExtractor.extract(target)
    }

    fun extractActiveFieldContext(): ActiveFieldContext {
        val target = findBestTargetNode(allowCachedFallback = true)
        return ActiveAppContextExtractor.extract(target)
    }

    fun requestImmediateKeyboardSync() {
        syncKeyboardVisibilityWithBubble()
    }

    fun syncKeyboardVisibilityWithBubble() {
        // 1. If OEM killed FloatingBubbleService while phone was idle, revive it right now!
        if (FloatingBubbleService.instance == null) {
            ensureBubbleServiceAliveAndBound()
        }

        val windowList = try { windows } catch (_: Exception) { emptyList() }
        if (windowList.isEmpty()) {
            ensureInteractiveWindowsFlagRegistered()
        }

        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }

        val focusedNode = try { activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
        val cached = lastFocusedEditableNode
        val hasFocus = (focusedNode != null && isCandidateInputNode(focusedNode)) ||
                (cached != null && isCandidateInputNode(cached))

        val snapshot = KeyboardVisibilityDetector.evaluateKeyboardState(
            context = this,
            windows = windowList,
            activeRoot = activeRoot,
            isImeInputStarted = isInputConnectionStarted,
            hasActiveEditableFocus = hasFocus,
            lastEditableTouchElapsedMs = lastEditableTouchElapsedMs
        )

        FloatingBubbleService.instance?.onKeyboardVisibilityChanged(
            keyboardVisible = snapshot.isKeyboardVisible,
            keyboardTopY = snapshot.keyboardTopY,
            isRestrictedAppOrField = snapshot.isRestrictedFieldOrApp
        )
        keyboardStripController?.updateKeyboardVisibilityAndPosition(
            isKeyboardOpen = snapshot.isKeyboardVisible && !snapshot.isRestrictedFieldOrApp,
            keyboardTopY = snapshot.keyboardTopY
        )
        mainHandler.post {
            onKeyboardVisibilityChanged?.invoke(snapshot.isKeyboardVisible)
        }
    }

    fun updateKeyboardChipState(state: KeyboardStripOverlayController.StripState) {
        mainHandler.post {
            keyboardStripController?.setState(state)
        }
    }

    fun onNativeAutofillChipShown() {
        mainHandler.post {
            keyboardStripController?.syncStripBoundsAndVisibility()
        }
    }

    fun onInlineAutofillChipTapped() {
        mainHandler.post {
            keyboardStripController?.setState(KeyboardStripOverlayController.StripState.RECORDING)
        }
    }

    fun checkInputFocusState() {
        syncKeyboardVisibilityWithBubble()
    }

    fun injectText(textToInject: String): Boolean {
        if (textToInject.isBlank()) return false
        return runBlocking {
            val outcome = injectTextSuspend(textToInject)
            outcome.status == InjectionStatus.INJECTED_PASTE || outcome.status == InjectionStatus.INJECTED_SET_TEXT
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        lastFocusedEditableNode = null
        keyboardStripController?.detach()
        keyboardStripController = null
        if (isBubbleServiceBound) {
            try {
                unbindService(bubbleServiceConnection)
            } catch (_: Exception) {}
            isBubbleServiceBound = false
        }
        super.onDestroy()
    }
}
