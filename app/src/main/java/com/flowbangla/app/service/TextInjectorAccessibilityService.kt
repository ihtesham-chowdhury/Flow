package com.flowbangla.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.flowbangla.app.FlowBanglaApp
import com.flowbangla.app.format.ActiveAppContextExtractor
import com.flowbangla.app.format.ActiveFieldContext

class TextInjectorAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    var onKeyboardVisibilityChanged: ((isKeyboardVisible: Boolean) -> Unit)? = null

    // Cache the most recently focused/clicked editable node so overlay clicks don't lose the cursor target
    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Flow TextInjectorAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Cache the focused editable node whenever focus, click, or text changes occur in an input field
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            val source = event.source
            if (source != null && source.isEditable) {
                lastFocusedEditableNode = AccessibilityNodeInfo.obtain(source)
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

    fun checkInputFocusState() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        if (!prefs.showOnlyWithKeyboard) {
            onKeyboardVisibilityChanged?.invoke(true)
            return
        }

        try {
            val windowList = windows
            var imeVisible = false
            for (w in windowList) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    imeVisible = true
                    break
                }
            }

            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val isEditable = focusedNode?.isEditable == true

            val isVisible = imeVisible || isEditable
            mainHandler.post {
                onKeyboardVisibilityChanged?.invoke(isVisible)
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        lastFocusedEditableNode = null
        if (instance == this) {
            instance = null
        }
    }

    fun extractActiveFieldContext(): ActiveFieldContext {
        val node = findTargetEditableNode()
        return ActiveAppContextExtractor.extract(node)
    }

    fun injectText(textToInject: String): Boolean {
        if (textToInject.isBlank()) return false

        // Always put the text in the clipboard first (prerequisite for ACTION_PASTE & safe backup)
        copyToClipboard(textToInject)

        val targetNode = findTargetEditableNode()
        if (targetNode != null) {
            // Strategy 1: Native ACTION_PASTE
            // This is Wispr Flow's technique: triggering ACTION_PASTE dispatches an authentic paste event
            // to the underlying view, committing text permanently at the cursor position without custom
            // text watchers (e.g., in Keep, Notes, WhatsApp, Chrome) clearing it.
            val pasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteResult) {
                Log.d(TAG, "Text injected successfully via ACTION_PASTE")
                return true
            }

            // Strategy 2: ACTION_SET_TEXT (fallback if the target view does not accept paste action)
            val existingText = targetNode.text?.toString() ?: ""
            val finalText = if (existingText.isBlank()) {
                textToInject
            } else {
                "$existingText $textToInject"
            }

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            val setResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (setResult) {
                Log.d(TAG, "Text injected successfully via ACTION_SET_TEXT")
                return true
            }
        }

        // Fallback: Copy to clipboard with user notification if no editable node could be bound
        fallbackToClipboard(textToInject, "Copied to clipboard / ক্লিপবোর্ডে কপি হয়েছে (ট্যাপ করে পেস্ট করুন)")
        return false
    }

    private fun findTargetEditableNode(): AccessibilityNodeInfo? {
        // 1. Check last cached focused editable node if still alive
        val cached = lastFocusedEditableNode
        if (cached != null) {
            try {
                if (cached.refresh() && cached.isEditable) {
                    return cached
                }
            } catch (_: Exception) {
            }
        }

        // 2. Check active window for focused input
        try {
            val root = rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                return focused
            }
            val editableInRoot = findEditableNode(root)
            if (editableInRoot != null) {
                return editableInRoot
            }
        } catch (_: Exception) {
        }

        // 3. Search across all application windows (bypassing keyboard and overlay windows)
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root ?: continue
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && focused.isEditable) {
                        return focused
                    }
                    val editable = findEditableNode(root)
                    if (editable != null) {
                        return editable
                    }
                }
            }
        } catch (_: Exception) {
        }

        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Flow Dictation", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun fallbackToClipboard(text: String, toastMessage: String) {
        copyToClipboard(text)
        mainHandler.post {
            Toast.makeText(applicationContext, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "TextInjectorService"
        var instance: TextInjectorAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${TextInjectorAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}
