package com.flowbangla.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.SttLanguage

/**
 * Hosted DIRECTLY inside TextInjectorAccessibilityService using TYPE_ACCESSIBILITY_OVERLAY.
 * Because it lives inside the OS-bound AccessibilityService and uses the Accessibility window token,
 * Xiaomi HyperOS, Samsung One UI, OPPO, and Vivo CANNOT kill it!
 *
 * Docks a Bitwarden-style inline voice chip right along the top suggestion bar of Gboard / Samsung Keyboard.
 */
class KeyboardStripOverlayController(
    private val a11yService: AccessibilityService,
    private val prefs: PreferencesManager,
    private val onStartRecording: () -> Unit,
    private val onConfirmRecording: () -> Unit,
    private val onCancelRecording: () -> Unit,
    private val onToggleLanguage: () -> Unit
) {
    private val windowManager = a11yService.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var chipView: KeyboardInlineChipView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attachIfNeeded() {
        if (isAdded || windowManager == null) return
        try {
            val density = a11yService.resources.displayMetrics.density
            val widthPx = (210f * density).toInt()
            val heightPx = (40f * density).toInt()

            val params = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (16f * density).toInt()
                y = (a11yService.resources.displayMetrics.heightPixels * 0.58f).toInt()
            }

            val view = KeyboardInlineChipView(a11yService)
            windowManager.addView(view, params)
            chipView = view
            layoutParams = params
            isAdded = true
            view.visibility = View.GONE // Hidden until keyboard opens!
        } catch (_: Throwable) {}
    }

    /**
     * Docks the Bitwarden-style chip flush with the top suggestion bar of Gboard / Samsung Keyboard!
     */
    fun updateKeyboardVisibilityAndPosition(isKeyboardOpen: Boolean, keyboardTopY: Int) {
        if (!isAdded) attachIfNeeded()
        val view = chipView ?: return
        val params = layoutParams ?: return

        if (!prefs.isMasterSwitchEnabled || !prefs.enableKeyboardBarChip) {
            view.visibility = View.GONE
            return
        }

        if (!isKeyboardOpen && view.currentState == ChipState.IDLE) {
            view.visibility = View.GONE
            return
        }

        val density = a11yService.resources.displayMetrics.density
        // Position flush on the top edge / suggestion strip of the keyboard
        val targetY = (keyboardTopY - (42f * density).toInt()).coerceAtLeast((80f * density).toInt())
        if (kotlin.math.abs(params.y - targetY) > (4f * density).toInt()) {
            params.y = targetY
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (_: Throwable) {}
        }

        if (view.visibility != View.VISIBLE) {
            view.invalidate()
            view.visibility = View.VISIBLE
        }
    }

    fun setState(newState: ChipState) {
        chipView?.let { view ->
            view.currentState = newState
            view.invalidate()
            if (newState == ChipState.INSERTED) {
                mainHandler.postDelayed({
                    if (view.currentState == ChipState.INSERTED) {
                        view.currentState = ChipState.IDLE
                        view.invalidate()
                    }
                }, 260L)
            }
        }
    }

    fun detach() {
        if (!isAdded) return
        try {
            chipView?.let { windowManager?.removeView(it) }
        } catch (_: Throwable) {}
        chipView = null
        isAdded = false
    }

    enum class ChipState { IDLE, RECORDING, PROCESSING, INSERTED }

    private inner class KeyboardInlineChipView(context: Context) : View(context) {
        var currentState: ChipState = ChipState.IDLE

        private val density = resources.displayMetrics.density
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#16181B") }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A2E33")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEBE3")
            textSize = 13.5f * density
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillRect = RectF()
        private val cancelZone = RectF()
        private val confirmZone = RectF()
        private val langBadgeZone = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 2f * density
            pillRect.set(pad, pad, w - pad, h - pad)
            val radius = h / 2f

            // Draw Ink & Danda Obsidian Pill Surface
            bgPaint.color = when (currentState) {
                ChipState.INSERTED -> Color.parseColor("#8FBF9F") // Sage flash on success
                else -> Color.parseColor("#16181B")
            }
            canvas.drawRoundRect(pillRect, radius, radius, bgPaint)
            canvas.drawRoundRect(pillRect, radius, radius, borderPaint)

            val isBangla = prefs.sttLanguage == SttLanguage.BENGALI || prefs.sttLanguage.localeTag.startsWith("bn")
            val centerY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

            when (currentState) {
                ChipState.IDLE -> {
                    // Left Vermilion Mic Dot + Label + Right Language Badge ('বাং' / 'EN')
                    dotPaint.color = Color.parseColor("#E8473A")
                    canvas.drawCircle(18f * density, h / 2f, 4.5f * density, dotPaint)

                    textPaint.color = Color.parseColor("#EEEBE3")
                    val label = if (isBangla) "বলুন • Flow" else "Speak • Flow"
                    canvas.drawText(label, 30f * density, centerY, textPaint)

                    // Right Language Toggle Badge
                    langBadgeZone.set(w - 46f * density, pad, w - pad, h - pad)
                    dotPaint.color = Color.parseColor("#22262B")
                    canvas.drawRoundRect(langBadgeZone, radius, radius, dotPaint)
                    val langStr = if (isBangla) "বাং" else "EN"
                    val langW = textPaint.measureText(langStr)
                    canvas.drawText(langStr, langBadgeZone.centerX() - langW / 2f, centerY, textPaint)
                }

                ChipState.RECORDING -> {
                    // Left Cancel [✕]  |  Center Recording Label  |  Right Confirm [✓]
                    cancelZone.set(pad, pad, 40f * density, h - pad)
                    confirmZone.set(w - 44f * density, pad, w - pad, h - pad)

                    dotPaint.color = Color.parseColor("#22262B")
                    canvas.drawRoundRect(cancelZone, radius, radius, dotPaint)
                    textPaint.color = Color.parseColor("#EEEBE3")
                    canvas.drawText("✕", cancelZone.centerX() - 5f * density, centerY, textPaint)

                    dotPaint.color = Color.parseColor("#E8473A")
                    canvas.drawCircle(54f * density, h / 2f, 4.5f * density, dotPaint)
                    val recText = if (isBangla) "শুনছি..." else "Listening..."
                    canvas.drawText(recText, 64f * density, centerY, textPaint)

                    // Right Sage Confirm [✓] Button
                    dotPaint.color = Color.parseColor("#8FBF9F")
                    canvas.drawRoundRect(confirmZone, radius, radius, dotPaint)
                    textPaint.color = Color.parseColor("#0D0E10")
                    canvas.drawText("✓", confirmZone.centerX() - 5f * density, centerY, textPaint)
                }

                ChipState.PROCESSING -> {
                    cancelZone.set(pad, pad, 40f * density, h - pad)
                    dotPaint.color = Color.parseColor("#22262B")
                    canvas.drawRoundRect(cancelZone, radius, radius, dotPaint)
                    textPaint.color = Color.parseColor("#EEEBE3")
                    canvas.drawText("✕", cancelZone.centerX() - 5f * density, centerY, textPaint)

                    dotPaint.color = Color.parseColor("#E3B257") // Ochre
                    canvas.drawCircle(54f * density, h / 2f, 4.5f * density, dotPaint)
                    val procText = if (isBangla) "সাজানো হচ্ছে..." else "Thinking..."
                    canvas.drawText(procText, 64f * density, centerY, textPaint)
                }

                ChipState.INSERTED -> {
                    textPaint.color = Color.parseColor("#0D0E10")
                    val okText = if (isBangla) "✓ লেখা বসেছে" else "✓ Inserted"
                    canvas.drawText(okText, 22f * density, centerY, textPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                val y = event.y
                when (currentState) {
                    ChipState.IDLE -> {
                        if (langBadgeZone.contains(x, y)) {
                            onToggleLanguage()
                            invalidate()
                        } else {
                            onStartRecording()
                        }
                    }
                    ChipState.RECORDING -> {
                        if (cancelZone.contains(x, y)) {
                            onCancelRecording()
                        } else if (confirmZone.contains(x, y)) {
                            onConfirmRecording()
                        }
                    }
                    ChipState.PROCESSING -> {
                        if (cancelZone.contains(x, y)) {
                            onCancelRecording()
                        }
                    }
                    ChipState.INSERTED -> {}
                }
                return true
            }
            return true
        }
    }
}
