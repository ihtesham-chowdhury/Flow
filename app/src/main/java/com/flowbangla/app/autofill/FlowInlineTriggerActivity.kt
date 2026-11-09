package com.flowbangla.app.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.flowbangla.app.service.FloatingBubbleService
import com.flowbangla.app.service.TextInjectorAccessibilityService

/**
 * Invisible, zero-UI trampoline Activity invoked when the user taps Flow's native
 * inline chip inside Gboard / Samsung Keyboard's suggestion bar.
 *
 * Why an Activity? Because Android's Autofill Dataset.setAuthentication(IntentSender)
 * strictly requires a PendingIntent.getActivity() IntentSender—and launching a lightweight
 * transparent Activity is 100% exempt from Xiaomi HyperOS background start blocks!
 */
class FlowInlineTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zero window animation
        overridePendingTransition(0, 0)

        // 1. Immediately wake up FloatingBubbleService & TextInjectorAccessibilityService
        // and start inline recording inside the Gboard strip!
        try {
            val serviceIntent = Intent(applicationContext, FloatingBubbleService::class.java).apply {
                action = FlowInlineAutofillService.ACTION_START_INLINE_DICTATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            TextInjectorAccessibilityService.instance?.onInlineAutofillChipTapped()
        } catch (_: Throwable) {}

        // 2. Return RESULT_CANCELED to AutofillManager so Gboard keeps the keyboard open
        // and does not overwrite the field until speech transcription finishes!
        setResult(RESULT_CANCELED)
        finish()
        overridePendingTransition(0, 0)
    }
}
