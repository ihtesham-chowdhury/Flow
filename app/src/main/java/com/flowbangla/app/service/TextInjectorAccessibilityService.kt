package com.flowbangla.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import kotlinx.coroutines.withContext
import java.text.Normalizer

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true

        // Ensure service flags include interactive windows and enhanced web accessibility
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }
        Log.i(TAG, "TextInjectorAccessibilityService connected with multi-window flags.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) {
            lastKnownPackageName = pkg
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val src = event.source
                if (src != null && isCandidateInputNode(src)) {
                    cacheEditableNode(src)
                    checkAndExecuteDeferredPaste(src)
                } else {
                    // Check if a newly opened bottom sheet (like YouTube/FB comment dialog) now has focus
                    val liveNode = findBestTargetNode(allowCachedFallback = false)
                    if (liveNode != null) {
                        cacheEditableNode(liveNode)
                        checkAndExecuteDeferredPaste(liveNode)
                    }
                }
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                checkInputFocusState()
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
     * Recognizes both standard EditTexts AND custom Litho / React Native / WebView comment inputs
     * used by Facebook, Messenger, YouTube, Instagram, and Chrome.
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

        return (supportsSetTextOrPaste && (node.isFocused || node.isFocusable || isKnownInputClass))
    }

    /**
     * 5-Step Live Target Node Resolver:
     * Searches active window focus, all interactive application/dialog windows (for YouTube/FB bottom sheets),
     * refreshed cached node, and deep DFS traversal.
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

        // Step 2: Search across ALL interactive windows (catches YouTube & Facebook comment bottom-sheet dialogs)
        val windowList: List<AccessibilityWindowInfo> = try { windows } catch (_: Exception) { emptyList() }
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

        // Step 4: Deep DFS search across all application windows for any focused or editable node
        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            val dfsFocused = findEditableByDfs(root, requireFocus = true)
            if (dfsFocused != null) return dfsFocused
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
     * Executes Clipboard Pre-load -> Tier 1 (Native Paste) -> Tier 2 (Focus + Paste Retry)
     * -> Tier 3 (Hint-Safe Cursor Spliced ACTION_SET_TEXT) -> Tier 4 (Deferred 1-Tap Recovery).
     */
    suspend fun injectTextSuspend(
        formattedText: String,
        isDeferredRetry: Boolean = false
    ): InjectionOutcome = withContext(Dispatchers.Main) {
        val normalizedText = Normalizer.normalize(formattedText, Normalizer.Form.NFC)
        if (normalizedText.isEmpty()) {
            return@withContext InjectionOutcome(InjectionStatus.FAILED, lastKnownPackageName, "Empty text")
        }

        // 0. Always set clipboard from THIS AccessibilityService's privileged context
        copyToClipboardPrivileged(normalizedText)
        // Wait 50ms so Android's ClipboardService propagates the new ClipData to target apps (Facebook/YouTube)
        delay(50L)

        var targetNode = findBestTargetNode(allowCachedFallback = true)

        if (targetNode == null) {
            // Queue for instant auto-paste the moment the user taps the comment box
            armDeferredPaste(normalizedText)
            return@withContext InjectionOutcome(
                status = InjectionStatus.DEFERRED_WAITING_FOR_TAP,
                targetPackage = lastKnownPackageName,
                detail = "No active input node found; armed deferred auto-paste."
            )
        }

        val pkg = targetNode.packageName?.toString() ?: lastKnownPackageName
        targetNode.refresh()

        // TIER 1: Direct Native ACTION_PASTE (if node already has input focus)
        if (targetNode.isFocused) {
            val pasted = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasted) {
                pendingDeferredText = null
                return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 1 ACTION_PASTE succeeded")
            }
        }

        // TIER 2: Request Input Focus + Click (for YouTube/Facebook comment boxes) -> Re-resolve -> ACTION_PASTE
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(65L)
        // Re-acquire node in case focusing swapped a decoy comment bar for a real EditText
        targetNode = findBestTargetNode(allowCachedFallback = true) ?: targetNode
        targetNode.refresh()

        val pastedAfterFocus = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pastedAfterFocus) {
            pendingDeferredText = null
            return@withContext InjectionOutcome(InjectionStatus.INJECTED_PASTE, pkg, "Tier 2 Focus + ACTION_PASTE succeeded")
        }

        // TIER 3: Hint-Safe, Cursor-Aware ACTION_SET_TEXT Fallback
        val setTextSuccess = performHintSafeSetText(targetNode, normalizedText)
        if (setTextSuccess) {
            pendingDeferredText = null
            return@withContext InjectionOutcome(InjectionStatus.INJECTED_SET_TEXT, pkg, "Tier 3 Hint-Safe ACTION_SET_TEXT succeeded")
        }

        // TIER 4: If all programmatic actions failed, arm Deferred Auto-Paste & show 1-Tap Paste Pill
        if (!isDeferredRetry) {
            armDeferredPaste(normalizedText)
        }
        return@withContext InjectionOutcome(
            status = InjectionStatus.DEFERRED_WAITING_FOR_TAP,
            targetPackage = pkg,
            detail = "Target rejected paste and set_text; armed deferred paste."
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

    fun extractActiveFieldContext(): ActiveFieldContext {
        val target = findBestTargetNode(allowCachedFallback = true)
        return ActiveAppContextExtractor.extract(target)
    }

    fun checkInputFocusState() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        if (!prefs.showOnlyWithKeyboard) {
            onKeyboardVisibilityChanged?.invoke(true)
            return
        }

        try {
            val windowList = try { windows } catch (_: Exception) { emptyList() }
            var imeVisible = false
            for (w in windowList) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    imeVisible = true
                    break
                }
            }

            val target = findBestTargetNode(allowCachedFallback = true)
            val isEditable = target != null && isCandidateInputNode(target)

            val isVisible = imeVisible || isEditable
            mainHandler.post {
                onKeyboardVisibilityChanged?.invoke(isVisible)
            }
        } catch (_: Exception) {
        }
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
        super.onDestroy()
    }
}
