package com.flowbangla.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.SttLanguage
import kotlin.math.abs
import kotlin.math.sin

typealias ChipState = KeyboardStripOverlayController.StripState

/**
 * Flow v3.9.0 — Senior Art Director "Ink & Danda" Studio Polish.
 * Renders Flow's voice trigger and recording controls DIRECTLY INSIDE Gboard / Samsung Keyboard's
 * suggestion strip (at `y = verifiedImeTopY + 4dp`), locked against coordinate drift.
 *
 * - IDLE: Sleek 76dp x 34dp pill (#23262B, 1dp specular #383D46 rim) with left Danda-Mic & Vermilion dot
 *   and right #2E323B language pill (1-tap switches বাং ↔ EN).
 * - RECORDING: In-strip pod with 30dp Cancel disc (✕), live timer & 7-bar Vermilion equalizer, and 38dp x 30dp Sage confirm pill (✓).
 * - PROCESSING: Ochre indicator with formatting feedback.
 * - INSERTED: Sage flash ("✓ লেখা বসেছে" / "✓ Inserted").
 */
class KeyboardStripOverlayController(
    private val a11yService: AccessibilityService,
    private val prefs: PreferencesManager,
    private val onStartRecording: () -> Unit,
    private val onConfirmRecording: () -> Unit,
    private val onCancelRecording: () -> Unit,
    private val onToggleLanguage: () -> Unit
) {
    private val windowManager = a11yService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var stripView: InStripChipView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var lastKnownKeyboardTopY: Int = 0
    private var isKeyboardCurrentlyOpen: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class StripState { IDLE, RECORDING, PROCESSING, INSERTED }

    fun attachIfNeeded() {
        if (isAdded) return
        try {
            val density = a11yService.resources.displayMetrics.density
            val compactW = (76f * density).toInt()
            val stripH = (34f * density).toInt()

            val params = WindowManager.LayoutParams(
                compactW,
                stripH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            val view = InStripChipView(a11yService)
            windowManager.addView(view, params)
            stripView = view
            layoutParams = params
            isAdded = true
            view.visibility = View.GONE
        } catch (_: Throwable) {}
    }

    /**
     * Calibrates overlay frame offset (status-bar / camera cutout) using view's actual screen location.
     * Prevents any vertical drift across keyboard open/close cycles.
     */
    fun computeExactInStripParamsY(
        view: View,
        currentParamsY: Int,
        measuredImeScreenTopY: Int,
        density: Float
    ): Int {
        val screenLoc = IntArray(2)
        view.getLocationOnScreen(screenLoc)
        val frameOffsetPx = if (view.visibility == View.VISIBLE && screenLoc[1] > 0) {
            screenLoc[1] - currentParamsY
        } else {
            0
        }
        return (measuredImeScreenTopY - frameOffsetPx) + (4f * density).toInt()
    }

    fun updateKeyboardVisibilityAndPosition(
        isKeyboardOpen: Boolean,
        keyboardTopY: Int,
        isMeasuredIme: Boolean = false
    ) {
        if (!isAdded) attachIfNeeded()
        isKeyboardCurrentlyOpen = isKeyboardOpen

        if (isMeasuredIme && keyboardTopY > 0) {
            lastKnownKeyboardTopY = keyboardTopY
            prefs.verifiedImeTopYPortrait = keyboardTopY
        } else if (keyboardTopY > 0 && lastKnownKeyboardTopY == 0) {
            val savedVerifiedY = prefs.verifiedImeTopYPortrait
            lastKnownKeyboardTopY = if (savedVerifiedY > 0) savedVerifiedY else keyboardTopY
        } else if (lastKnownKeyboardTopY == 0 && prefs.verifiedImeTopYPortrait > 0) {
            lastKnownKeyboardTopY = prefs.verifiedImeTopYPortrait
        }

        syncStripBoundsAndVisibility()
    }

    fun setState(newState: StripState) {
        val view = stripView ?: return
        if (view.currentState != newState) {
            view.currentState = newState
            syncStripBoundsAndVisibility()
            view.invalidate()
            if (newState == StripState.INSERTED) {
                mainHandler.postDelayed({
                    if (view.currentState == StripState.INSERTED) {
                        view.currentState = StripState.IDLE
                        syncStripBoundsAndVisibility()
                        view.invalidate()
                    }
                }, 300L)
            }
        }
    }

    /**
     * Positions the chip STRICTLY INSIDE Gboard's top suggestion strip:
     * `y = verifiedImeTopY + 4dp` (NEVER guessing screenHeight * 0.62f!).
     */
    fun syncStripBoundsAndVisibility() {
        val view = stripView ?: return
        val params = layoutParams ?: return

        if (!prefs.isMasterSwitchEnabled || !prefs.enableKeyboardBarChip) {
            view.visibility = View.GONE
            return
        }

        val state = view.currentState
        if (!isKeyboardCurrentlyOpen && state == StripState.IDLE) {
            view.visibility = View.GONE
            return
        }

        val metrics = a11yService.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val kbdTop = if (lastKnownKeyboardTopY > 0) {
            lastKnownKeyboardTopY
        } else if (prefs.verifiedImeTopYPortrait > 0) {
            prefs.verifiedImeTopYPortrait
        } else {
            (metrics.heightPixels * 0.60f).toInt()
        }

        val targetY = computeExactInStripParamsY(view, params.y, kbdTop, density)
        val targetH: Int
        val targetW: Int
        val targetX: Int

        if (state == StripState.IDLE) {
            // Sleek 76dp x 34dp pill tucked neatly to the left of Gboard's right-hand mic icon (48dp)
            targetW = (76f * density).toInt()
            targetH = (34f * density).toInt()
            targetX = screenW - targetW - (48f * density).toInt()
        } else {
            // Expanded in-strip recording pod between Gboard's left [⊞] icon (52dp) and right [🎤] icon (52dp)
            targetX = (52f * density).toInt()
            targetW = (screenW - (104f * density).toInt()).coerceAtLeast((180f * density).toInt())
            targetH = (36f * density).toInt()
        }

        val needsLayoutUpdate = abs(params.y - targetY) > 2 ||
            abs(params.x - targetX) > 2 ||
            params.width != targetW ||
            params.height != targetH

        if (needsLayoutUpdate) {
            params.x = targetX
            params.y = targetY
            params.width = targetW
            params.height = targetH
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Throwable) {}
        }

        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        view.invalidate()
    }

    /**
     * Temporarily toggles FLAG_NOT_FOCUSABLE off for 15ms to satisfy Android 10-15
     * background clipboard write checks.
     */
    fun performFocusedClipboardWrite(clipboard: ClipboardManager, clip: ClipData) {
        val view = stripView ?: return
        val params = layoutParams ?: return
        try {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(view, params)
            clipboard.setPrimaryClip(clip)
            mainHandler.postDelayed({
                try {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    windowManager.updateViewLayout(view, params)
                } catch (_: Throwable) {}
            }, 15L)
        } catch (t: Throwable) {
            Log.w("KeyboardStripOverlay", "Focused clipboard write fallback: ${t.message}")
        }
    }

    fun detach() {
        if (!isAdded) return
        try {
            stripView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {}
        stripView = null
        isAdded = false
    }

    private inner class InStripChipView(context: Context) : View(context) {
        var currentState: StripState = StripState.IDLE
            set(value) {
                if (field != value) {
                    field = value
                    if (value == StripState.RECORDING) {
                        recordingStartMs = SystemClock.elapsedRealtime()
                        startTicker()
                    } else {
                        stopTicker()
                    }
                }
            }

        private var recordingStartMs = 0L
        private val tickerRunnable = object : Runnable {
            override fun run() {
                if (currentState == StripState.RECORDING) {
                    invalidate()
                    mainHandler.postDelayed(this, 80L)
                }
            }
        }

        private fun startTicker() {
            mainHandler.removeCallbacks(tickerRunnable)
            mainHandler.post(tickerRunnable)
        }

        private fun stopTicker() {
            mainHandler.removeCallbacks(tickerRunnable)
        }

        private val density = resources.displayMetrics.density
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#383D46")
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEBE3")
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEBE3")
            textSize = 12f * density
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val eqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8473A")
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }

        private val pillRect = RectF()
        private val cancelBounds = RectF()
        private val confirmBounds = RectF()
        private val langPillBounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 1f * density
            pillRect.set(pad, pad, w - pad, h - pad)
            val r = h / 2f

            // Surface background per State
            bgPaint.color = when (currentState) {
                StripState.INSERTED -> Color.parseColor("#8FBF9F")
                StripState.RECORDING -> Color.parseColor("#1B1D21")
                StripState.PROCESSING -> Color.parseColor("#22252A")
                else -> Color.parseColor("#23262B")
            }
            canvas.drawRoundRect(pillRect, r, r, bgPaint)
            if (currentState != StripState.INSERTED) {
                canvas.drawRoundRect(pillRect, r, r, borderPaint)
            }

            val isBangla = prefs.sttLanguage == SttLanguage.BENGALI
            val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

            when (currentState) {
                StripState.IDLE -> {
                    // IDLE: 76dp x 34dp pill
                    // Left: Danda-Mic vector icon with Vermilion dot
                    val micCx = 18f * density
                    drawMiniDandaMicIcon(canvas, cx = micCx, cy = h / 2f, color = Color.parseColor("#EEEBE3"))

                    // Right: Inner Language Pill (#2E323B)
                    val langW = 32f * density
                    val langH = 24f * density
                    val langRight = w - 5f * density
                    val langLeft = langRight - langW
                    val langTop = (h - langH) / 2f
                    val langBottom = langTop + langH
                    langPillBounds.set(langLeft, langTop, langRight, langBottom)

                    bgPaint.color = Color.parseColor("#2E323B")
                    val lr = langH / 2f
                    canvas.drawRoundRect(langPillBounds, lr, lr, bgPaint)

                    // Language label
                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 11f * density
                    val langStr = if (isBangla) "বাং" else "EN"
                    val lw = textPaint.measureText(langStr)
                    canvas.drawText(langStr, langPillBounds.centerX() - lw / 2f, textY, textPaint)
                }

                StripState.RECORDING -> {
                    // RECORDING: Pod with Cancel disc (✕), live timer, 7-bar equalizer, and Confirm pill (✓)
                    // Left: 30dp Cancel disc (✕)
                    val discSize = 28f * density
                    val discPad = (h - discSize) / 2f
                    cancelBounds.set(discPad, discPad, discPad + discSize, discPad + discSize)
                    bgPaint.color = Color.parseColor("#2C3036")
                    canvas.drawRoundRect(cancelBounds, discSize / 2f, discSize / 2f, bgPaint)

                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 12f * density
                    val xW = textPaint.measureText("✕")
                    canvas.drawText("✕", cancelBounds.centerX() - xW / 2f, textY, textPaint)

                    // Center: Live Timer + 7-Bar Equalizer + Status
                    val elapsedSec = ((SystemClock.elapsedRealtime() - recordingStartMs) / 1000L).coerceAtLeast(0L)
                    val timerStr = String.format("%d:%02d", elapsedSec / 60, elapsedSec % 60)
                    textPaint.color = Color.parseColor("#D5D8DF")
                    textPaint.textSize = 11.5f * density
                    val timerStartX = cancelBounds.right + 8f * density
                    canvas.drawText(timerStr, timerStartX, textY, textPaint)

                    // 7-bar Vermilion equalizer
                    val timerW = textPaint.measureText(timerStr)
                    val eqStartX = timerStartX + timerW + 8f * density
                    val nowMs = SystemClock.elapsedRealtime()
                    for (i in 0 until 7) {
                        val phase = (nowMs / 120.0) + (i * 0.7)
                        val barNorm = (sin(phase) * 0.45 + 0.55).toFloat().coerceIn(0.2f, 1.0f)
                        val barH = (4f + barNorm * 12f) * density
                        val barX = eqStartX + (i * 5.2f * density)
                        val barTop = (h - barH) / 2f
                        val barBottom = barTop + barH
                        canvas.drawLine(barX, barTop, barX, barBottom, eqPaint)
                    }

                    // Optional short text if room permits
                    val statusStr = if (isBangla) "শুনছি…" else "Listening…"
                    val eqEndX = eqStartX + (7 * 5.2f * density) + 6f * density
                    textPaint.color = Color.parseColor("#9EA3AB")
                    textPaint.textSize = 11f * density
                    val confirmW = 38f * density
                    val rightConfirmLeft = w - discPad - confirmW
                    if (rightConfirmLeft - eqEndX > textPaint.measureText(statusStr) + 8f * density) {
                        canvas.drawText(statusStr, eqEndX, textY, textPaint)
                    }

                    // Right: 38dp x 30dp Confirm Pill (✓) in Sage (#8FBF9F)
                    confirmBounds.set(rightConfirmLeft, discPad, rightConfirmLeft + confirmW, discPad + discSize)
                    bgPaint.color = Color.parseColor("#8FBF9F")
                    val cr = discSize / 2f
                    canvas.drawRoundRect(confirmBounds, cr, cr, bgPaint)

                    textPaint.color = Color.parseColor("#0D0E10")
                    textPaint.textSize = 14f * density
                    val tickW = textPaint.measureText("✓")
                    canvas.drawText("✓", confirmBounds.centerX() - tickW / 2f, textY, textPaint)
                }

                StripState.PROCESSING -> {
                    // Left Cancel disc
                    val discSize = 28f * density
                    val discPad = (h - discSize) / 2f
                    cancelBounds.set(discPad, discPad, discPad + discSize, discPad + discSize)
                    bgPaint.color = Color.parseColor("#2C3036")
                    canvas.drawRoundRect(cancelBounds, discSize / 2f, discSize / 2f, bgPaint)

                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 12f * density
                    val xW = textPaint.measureText("✕")
                    canvas.drawText("✕", cancelBounds.centerX() - xW / 2f, textY, textPaint)

                    // Ochre processing dot
                    bgPaint.color = Color.parseColor("#E3B257")
                    val procCx = cancelBounds.right + 14f * density
                    canvas.drawCircle(procCx, h / 2f, 4f * density, bgPaint)

                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 12f * density
                    val procStr = if (isBangla) "সাজানো হচ্ছে…" else "Formatting…"
                    canvas.drawText(procStr, procCx + 10f * density, textY, textPaint)
                }

                StripState.INSERTED -> {
                    textPaint.color = Color.parseColor("#0D0E10")
                    textPaint.textSize = 13f * density
                    val okStr = if (isBangla) "✓ লেখা বসেছে" else "✓ Inserted"
                    val okW = textPaint.measureText(okStr)
                    canvas.drawText(okStr, (w - okW) / 2f, textY, textPaint)
                }
            }
        }

        private fun drawMiniDandaMicIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
            iconPaint.color = color
            val r = 3f * density
            val headRect = RectF(cx - r, cy - 6.5f * density, cx + r, cy + 1.5f * density)
            canvas.drawRoundRect(headRect, r, r, iconPaint)
            val arcRect = RectF(cx - 5.5f * density, cy - 4f * density, cx + 5.5f * density, cy + 4.5f * density)
            canvas.drawArc(arcRect, 0f, 180f, false, iconPaint)
            canvas.drawLine(cx, cy + 4.5f * density, cx, cy + 7.5f * density, iconPaint)

            // Accent Vermilion dot at top right of mic
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.parseColor("#E8473A")
            }
            canvas.drawCircle(cx + 4.5f * density, cy - 5.5f * density, 1.8f * density, dotPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                when (currentState) {
                    StripState.IDLE -> {
                        // If tapped right side on the inner language pill: toggle language immediately!
                        if (x >= langPillBounds.left - 6f * density) {
                            onToggleLanguage()
                            invalidate()
                        } else {
                            // Tapped left side: start recording immediately!
                            onStartRecording()
                        }
                    }
                    StripState.RECORDING -> {
                        if (x <= cancelBounds.right + 12f * density) {
                            onCancelRecording()
                        } else if (x >= confirmBounds.left - 12f * density) {
                            onConfirmRecording()
                        }
                    }
                    StripState.PROCESSING -> {
                        if (x <= cancelBounds.right + 12f * density) {
                            onCancelRecording()
                        }
                    }
                    StripState.INSERTED -> {}
                }
                return true
            }
            return true
        }
    }
}
