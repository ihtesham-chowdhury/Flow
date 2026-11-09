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
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.autofill.AutofillId
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
    }

    override fun onConnected() {
        super.onConnected()
        Log.i(TAG, "FlowInlineAutofillService connected by Android OS.")
        wakeUpFlowOverlayBridge()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        // 1. OS WAKE-UP GUARANTEE: Whenever ANY text field triggers Autofill,
        // Android's system_server wakes up our process and runs this callback!
        wakeUpFlowOverlayBridge()

        val contexts: List<FillContext> = request.fillContexts
        val structure: AssistStructure = contexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        val focusedField = findFocusedEditableAutofillNode(structure)
        if (focusedField == null || focusedField.isPassword) {
            callback.onSuccess(null)
            return
        }

        // 2. On Android 11+ (API 30+), build the Bitwarden-style Gboard Inline Suggestion Chip!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val inlineRequest = request.inlineSuggestionsRequest
            val specs = inlineRequest?.inlinePresentationSpecs
            if (inlineRequest != null && !specs.isNullOrEmpty() && inlineRequest.maxSuggestionCount > 0) {
                val response = buildInlineVoiceChipResponse(focusedField.autofillId, specs[0])
                callback.onSuccess(response)
                return
            }
        }

        callback.onSuccess(null)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun wakeUpFlowOverlayBridge() {
        try {
            val prefs = PreferencesManager(applicationContext)
            if (prefs.isMasterSwitchEnabled) {
                FloatingBubbleService.startIfPermitted(applicationContext)
                TextInjectorAccessibilityService.instance?.requestImmediateKeyboardSync()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Wake-up bridge warning: ${t.message}")
        }
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildInlineVoiceChipResponse(
        targetAutofillId: AutofillId,
        spec: android.widget.inline.InlinePresentationSpec
    ): FillResponse? {
        val styles = spec.style
        if (!UiVersions.getVersions(styles).contains(UiVersions.INLINE_UI_VERSION_1)) {
            return null
        }

        val prefs = PreferencesManager(applicationContext)
        val isBangla = prefs.sttLanguage == SttLanguage.BENGALI || prefs.sttLanguage.localeTag.startsWith("bn")
        val chipTitle = if (isBangla) "বলুন • Flow" else "Speak • Flow"
        val chipSubtitle = if (isBangla) "বাং" else "EN"

        // Intent that starts Flow voice recording when the user taps the chip inside Gboard!
        val triggerIntent = Intent(applicationContext, FloatingBubbleService::class.java).apply {
            action = ACTION_START_INLINE_DICTATION
        }
        val triggerPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext,
                2001,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                applicationContext,
                2001,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val attributionIntent = PendingIntent.getActivity(
            applicationContext,
            2002,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = R.mipmap.ic_launcher
        val slice = InlineSuggestionUi.newContentBuilder(attributionIntent)
            .setTitle(chipTitle)
            .setSubtitle(chipSubtitle)
            .setStartIcon(Icon.createWithResource(applicationContext, iconRes))
            .setContentDescription(chipTitle)
            .build()
            .slice

        val inlinePresentation = InlinePresentation(slice, spec, /* pinned = */ true)

        val fallbackRv = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, chipTitle)
        }

        val dataset = Dataset.Builder(fallbackRv)
            .setValue(targetAutofillId, null)
            .setInlinePresentation(inlinePresentation)
            .setAuthentication(triggerPendingIntent.intentSender)
            .build()

        return FillResponse.Builder()
            .addDataset(dataset)
            .build()
    }

    private data class FocusedAutofillNode(
        val autofillId: AutofillId,
        val isPassword: Boolean
    )

    private fun findFocusedEditableAutofillNode(structure: AssistStructure): FocusedAutofillNode? {
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i)?.rootViewNode ?: continue
            val found = searchViewNode(root)
            if (found != null) return found
        }
        return null
    }

    private fun searchViewNode(node: AssistStructure.ViewNode): FocusedAutofillNode? {
        val afId = node.autofillId
        val inputType = node.inputType
        val isPass = (inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
            (inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0 ||
            (inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 ||
            (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0

        if (node.isFocused && afId != null && (inputType != 0 || node.className?.contains("EditText") == true)) {
            return FocusedAutofillNode(afId, isPass)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            val found = searchViewNode(child)
            if (found != null) return found
        }
        return null
    }
}
