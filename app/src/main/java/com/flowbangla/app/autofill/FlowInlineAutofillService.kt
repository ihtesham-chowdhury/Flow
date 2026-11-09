package com.flowbangla.app.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.flowbangla.app.R
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.SttLanguage
import com.flowbangla.app.service.FloatingBubbleService
import com.flowbangla.app.service.TextInjectorAccessibilityService

@RequiresApi(Build.VERSION_CODES.O)
class FlowInlineAutofillService : AutofillService() {

    companion object {
        private const val TAG = "FlowInlineAutofill"
        const val ACTION_START_INLINE_DICTATION = "com.flowbangla.app.action.START_INLINE_DICTATION"

        // Tracks whether the native Gboard inline chip was delivered for the currently focused field
        @Volatile
        var isNativeInlineChipActiveOnCurrentField: Boolean = false
    }

    override fun onConnected() {
        super.onConnected()
        wakeUpFlowServices()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        // 1. OS WAKE-UP: Android's system_server calls this whenever an input field is tapped,
        // automatically waking Flow up even if it was swiped/killed in the background!
        wakeUpFlowServices()

        val structure: AssistStructure = request.fillContexts.lastOrNull()?.structure ?: run {
            isNativeInlineChipActiveOnCurrentField = false
            callback.onSuccess(null)
            return
        }

        // Ignore our own app or banking apps
        val pkg = structure.activityComponent?.packageName.orEmpty()
        if (pkg == packageName) {
            callback.onSuccess(null)
            return
        }

        val targetNode = findFocusedOrEditableNode(structure)
        if (targetNode == null || targetNode.isPassword) {
            isNativeInlineChipActiveOnCurrentField = false
            callback.onSuccess(null)
            return
        }

        // 2. Render compact Bitwarden-style inline chip inside Gboard / Samsung Keyboard strip (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val inlineReq = request.inlineSuggestionsRequest
            val specs = inlineReq?.inlinePresentationSpecs
            if (inlineReq != null && !specs.isNullOrEmpty() && inlineReq.maxSuggestionCount > 0) {
                val response = buildCompactGboardStripChip(targetNode.autofillId, specs.first())
                if (response != null) {
                    isNativeInlineChipActiveOnCurrentField = true
                    // Hide the fallback idle overlay chip since native Gboard chip is now in the strip!
                    TextInjectorAccessibilityService.instance?.onNativeAutofillChipShown()
                    callback.onSuccess(response)
                    return
                }
            }
        }

        isNativeInlineChipActiveOnCurrentField = false
        callback.onSuccess(null)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun wakeUpFlowServices() {
        try {
            val prefs = PreferencesManager(applicationContext)
            if (prefs.isMasterSwitchEnabled) {
                FloatingBubbleService.startIfPermitted(applicationContext)
                TextInjectorAccessibilityService.instance?.requestImmediateKeyboardSync()
            }
        } catch (_: Throwable) {}
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildCompactGboardStripChip(
        autofillId: AutofillId,
        spec: android.widget.inline.InlinePresentationSpec
    ): FillResponse? {
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) {
            return null
        }

        val prefs = PreferencesManager(applicationContext)
        val isBangla = prefs.sttLanguage == SttLanguage.BENGALI
        // Compact label so it looks like a sleek native button inside Gboard's suggestion strip
        // and does NOT take over the whole suggestion bar!
        val compactLabel = if (isBangla) "বলুন (বাং)" else "Flow (EN)"

        // CRITICAL FIX: Dataset.setAuthentication MUST use PendingIntent.getActivity!
        val triggerActivityIntent = Intent(applicationContext, FlowInlineTriggerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val authPendingIntent = PendingIntent.getActivity(
            applicationContext,
            3001,
            triggerActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val attributionPendingIntent = PendingIntent.getActivity(
            applicationContext,
            3002,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val slice = InlineSuggestionUi.newContentBuilder(attributionPendingIntent)
            .setStartIcon(Icon.createWithResource(applicationContext, R.drawable.ic_launcher_foreground))
            .setTitle(compactLabel)
            .setContentDescription("Flow Voice Dictation")
            .build()
            .slice

        // pinned = true pins our compact mic button to the left/start of Gboard's suggestion strip
        // while allowing regular word suggestions to scroll beside it!
        val inlinePresentation = InlinePresentation(slice, spec, /* pinned = */ true)

        val fallbackRv = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, compactLabel)
        }

        val dataset = Dataset.Builder(fallbackRv)
            .setValue(autofillId, AutofillValue.forText(" "))
            .setInlinePresentation(inlinePresentation)
            .setAuthentication(authPendingIntent.intentSender)
            .build()

        return FillResponse.Builder()
            .addDataset(dataset)
            .build()
    }

    private data class TargetAutofillNode(val autofillId: AutofillId, val isPassword: Boolean)

    /**
     * Broad, Bitwarden-grade AssistStructure scanner:
     * Matches focused EditTexts, Custom Views (Google Keep, Facebook), and WebView HTML inputs.
     */
    private fun findFocusedOrEditableNode(structure: AssistStructure): TargetAutofillNode? {
        var fallbackEditable: TargetAutofillNode? = null
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i)?.rootViewNode ?: continue
            val focused = scanNodeRecursive(root, requireFocus = true)
            if (focused != null) return focused
            if (fallbackEditable == null) {
                fallbackEditable = scanNodeRecursive(root, requireFocus = false)
            }
        }
        return fallbackEditable
    }

    private fun scanNodeRecursive(node: AssistStructure.ViewNode, requireFocus: Boolean): TargetAutofillNode? {
        val afId = node.autofillId
        val inputType = node.inputType
        val isPass = (inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
            (inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0 ||
            (inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 ||
            (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0 ||
            node.autofillHints?.any { it.contains("password", ignoreCase = true) } == true

        val cls = node.className.orEmpty()
        val isEditableCandidate = afId != null && (
            node.autofillType == View.AUTOFILL_TYPE_TEXT ||
                inputType != 0 ||
                cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoComplete", ignoreCase = true) ||
                node.htmlInfo != null
            )

        if (isEditableCandidate && (!requireFocus || node.isFocused)) {
            return TargetAutofillNode(afId!!, isPass)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            val found = scanNodeRecursive(child, requireFocus)
            if (found != null) return found
        }
        return null
    }
}
