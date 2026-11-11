package com.flowbangla.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.SttLanguage

/**
 * Claude-style ('1000376730.mp4') real-time streaming speech-to-text preview card.
 * Floats cleanly above the keyboard suggestion strip (or floating pod) during RECORDING,
 * rendering live partial words in editorial Italic Serif (#C8CCD4) up to 3 lines.
 * Automatically hides the instant recording stops or if the user toggles 'Live Preview' OFF.
 */
class LiveTranscriptionPreviewController(
    private val windowContext: Context,
    private val prefs: PreferencesManager
) {
    private val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var containerView: FrameLayout? = null
    private var previewTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false

    fun attachIfNeeded() {
        if (isAdded) return
        try {
            val metrics = windowContext.resources.displayMetrics
            val density = metrics.density
            val cardWidth = metrics.widthPixels - (32f * density).toInt()

            val backgroundDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#F0111316")) // 94% alpha Deep Obsidian
                cornerRadius = 16f * density
                setStroke((1f * density).toInt(), Color.parseColor("#2A2E33"))
            }

            val tv = TextView(windowContext).apply {
                setTextColor(Color.parseColor("#C8CCD4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.create("serif", Typeface.ITALIC)
                setLineSpacing(4f * density, 1.05f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.START // Auto-scrolls so latest spoken words are always visible!
                val hPad = (16f * density).toInt()
                val vPad = (12f * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
            }

            val container = FrameLayout(windowContext).apply {
                background = backgroundDrawable
                addView(
                    tv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                visibility = View.GONE
            }

            val windowType = if (windowContext is android.accessibilityservice.AccessibilityService) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val params = WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (metrics.heightPixels * 0.45f).toInt()
            }

            windowManager.addView(container, params)
            containerView = container
            previewTextView = tv
            layoutParams = params
            isAdded = true
        } catch (_: Throwable) {}
    }

    /**
     * Updates the live streaming text in real time (called on Main Thread).
     */
    fun updateLiveText(partialTranscript: String, anchorTopY: Int = 0) {
        if (!prefs.enableLiveTranscriptionPreview) {
            hide()
            return
        }
        if (!isAdded) attachIfNeeded()
        val container = containerView ?: return
        val tv = previewTextView ?: return
        val params = layoutParams ?: return

        val clean = partialTranscript.trim()
        val isBangla = prefs.sttLanguage == SttLanguage.BENGALI
        if (clean.isEmpty()) {
            tv.text = if (isBangla) "বলুন, আপনার কথা এখানে সরাসরি দেখা যাবে…" else "Speak naturally, live words appear here…"
            tv.setTextColor(Color.parseColor("#7A8089"))
        } else {
            // Keep the last ~140 characters so the 3-line window always shows the newest spoken words
            val tailText = if (clean.length > 150) "…" + clean.takeLast(145).substringAfter(' ') else clean
            tv.text = tailText
            tv.setTextColor(Color.parseColor("#D5D8DF"))
        }

        val density = windowContext.resources.displayMetrics.density
        container.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredH = container.measuredHeight.coerceAtLeast((48f * density).toInt())
        val defaultTopY = (windowContext.resources.displayMetrics.heightPixels * 0.50f).toInt()
        val baseAnchor = if (anchorTopY > 100) anchorTopY else defaultTopY
        val targetY = (baseAnchor - measuredH - (10f * density).toInt()).coerceAtLeast((64f * density).toInt())

        if (kotlin.math.abs(params.y - targetY) > 4) {
            params.y = targetY
            try {
                windowManager.updateViewLayout(container, params)
            } catch (_: Throwable) {}
        }

        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
        }
    }

    fun hide() {
        containerView?.visibility = View.GONE
    }

    fun detach() {
        if (!isAdded) return
        try {
            containerView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {}
        containerView = null
        previewTextView = null
        isAdded = false
    }
}
