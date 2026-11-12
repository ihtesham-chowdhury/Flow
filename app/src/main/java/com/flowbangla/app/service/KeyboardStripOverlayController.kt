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
 * Flow v4.0.0 — Studio Inline Bar Symmetry & Balanced 3-Zone Layout.
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
            val compactW = (40f * density).toInt()
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

    fun pushAmplitudeSample(normAmp: Float, elapsedSec: Int) {
        stripView?.pushAmplitudeSample(normAmp, elapsedSec)
    }

    fun hideImmediatelyOnKeyboardClose() {
        isKeyboardCurrentlyOpen = false
        stripView?.visibility = View.GONE
        // If recording was active on the inline keyboard bar when the user closed the keyboard,
        // immediately finish/stop the inline bar so it never floats over the screen without a keyboard!
        if (stripView?.currentState == StripState.RECORDING) {
            stripView?.currentState = StripState.IDLE
            onConfirmRecording()
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
            // Pure 40dp x 34dp studio mic capsule — no 'EN' or 'বাং' text!
            targetW = (40f * density).toInt()
            targetH = (34f * density).toInt()
            targetX = screenW - targetW - (54f * density).toInt()
        } else {
            // Expanded studio bar between Gboard's left [⊞] icon (52dp) and right [🎤] divider (58dp)
            // Using 58dp right margin guarantees we NEVER touch Gboard's vertical divider line!
            targetX = (52f * density).toInt()
            targetW = (screenW - (110f * density).toInt()).coerceAtLeast((180f * density).toInt())
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

        var recordingElapsedSeconds: Int = 0
        var currentNormalizedAmplitude: Float = 0.15f
        private val waveformHistory = FloatArray(15) { 0.18f }

        fun pushAmplitudeSample(normAmp: Float, elapsedSec: Int) {
            recordingElapsedSeconds = elapsedSec
            currentNormalizedAmplitude = normAmp.coerceIn(0.08f, 1.0f)
            System.arraycopy(waveformHistory, 1, waveformHistory, 0, waveformHistory.size - 1)
            waveformHistory[waveformHistory.size - 1] = currentNormalizedAmplitude
            invalidate()
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
            val hPad = 3f * density
            val vPad = 2f * density
            pillRect.set(hPad, vPad, w - hPad, h - vPad)
            val r = pillRect.height() / 2f

            // 1. Studio Obsidian Capsule Background (#23262B in IDLE, #141619 in RECORDING/PROCESSING, #8FBF9F in INSERTED)
            bgPaint.color = when (currentState) {
                StripState.INSERTED -> Color.parseColor("#8FBF9F")
                StripState.RECORDING, StripState.PROCESSING -> Color.parseColor("#141619")
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
                    // Pure 40dp x 34dp studio mic capsule — no 'EN' or 'বাং' text!
                    drawMiniDandaMicIcon(canvas, cx = w / 2f, cy = h / 2f, color = Color.parseColor("#EEEBE3"))
                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#E8473A")
                    }
                    canvas.drawCircle(w / 2f + 6.5f * density, h / 2f - 6.5f * density, 2.3f * density, dotPaint)
                }

                StripState.RECORDING -> {
                    val innerGap = 3.5f * density
                    val sideBtnW = 36f * density
                    cancelBounds.set(
                        pillRect.left + innerGap,
                        pillRect.top + innerGap,
                        pillRect.left + innerGap + sideBtnW,
                        pillRect.bottom - innerGap
                    )
                    confirmBounds.set(
                        pillRect.right - innerGap - sideBtnW,
                        pillRect.top + innerGap,
                        pillRect.right - innerGap,
                        pillRect.bottom - innerGap
                    )
                    val btnRadius = cancelBounds.height() / 2f

                    // LEFT ZONE: Cancel (✕) Button
                    bgPaint.color = Color.parseColor("#282C33")
                    canvas.drawRoundRect(cancelBounds, btnRadius, btnRadius, bgPaint)
                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 13f * density
                    val xW = textPaint.measureText("✕")
                    canvas.drawText("✕", cancelBounds.centerX() - xW / 2f, textY, textPaint)

                    // RIGHT ZONE: Confirm (✓) Button in Sage (#8FBF9F)
                    bgPaint.color = Color.parseColor("#8FBF9F")
                    canvas.drawRoundRect(confirmBounds, btnRadius, btnRadius, bgPaint)
                    textPaint.color = Color.parseColor("#0D0E10")
                    val tickW = textPaint.measureText("✓")
                    canvas.drawText("✓", confirmBounds.centerX() - tickW / 2f, textY, textPaint)

                    // CENTER ZONE: Mathematically Centered [ 🔴 0:12  |  13-Bar Waveform  |  Listening ]
                    val mins = recordingElapsedSeconds / 60
                    val secs = recordingElapsedSeconds % 60
                    val timerStr = String.format("%d:%02d", mins, secs)
                    val statusStr = if (isBangla) "শুনছি" else "Listening"

                    textPaint.textSize = 12f * density
                    val timerW = textPaint.measureText(timerStr)
                    val statusW = textPaint.measureText(statusStr)

                    val dotSize = 6f * density
                    val gap = 8f * density
                    val barCount = 13
                    val barSpacing = 3.2f * density
                    val waveformTotalW = barCount * barSpacing

                    val totalClusterW = dotSize + (6f * density) + timerW + gap + waveformTotalW + gap + statusW
                    val availableCenterStart = cancelBounds.right + 6f * density
                    val availableCenterEnd = confirmBounds.left - 6f * density
                    val availableWidth = availableCenterEnd - availableCenterStart

                    // Perfect horizontal center anchor inside the middle zone!
                    var cursorX = availableCenterStart + ((availableWidth - totalClusterW) / 2f).coerceAtLeast(2f * density)

                    // 1. Pulsing Vermilion Recording Dot
                    bgPaint.color = Color.parseColor("#E8473A")
                    canvas.drawCircle(cursorX + dotSize / 2f, h / 2f, dotSize / 2f, bgPaint)
                    cursorX += dotSize + 6f * density

                    // 2. Crisp Tabular Timer ('0:12')
                    textPaint.color = Color.parseColor("#EEEBE3")
                    canvas.drawText(timerStr, cursorX, textY, textPaint)
                    cursorX += timerW + gap

                    // 3. Claude-Style 13-Bar Symmetric Micro-Waveform with 60fps oscillation
                    val maxBarH = h * 0.56f
                    val minBarH = 3.5f * density
                    val centerIdx = barCount / 2
                    val nowMs = System.currentTimeMillis()
                    for (i in 0 until barCount) {
                        val distFromCenter = kotlin.math.abs(i - centerIdx).toFloat() / centerIdx.toFloat()
                        val envelope = 1.0f - (distFromCenter * 0.42f)
                        val histSample = waveformHistory[(i + waveformHistory.size - barCount).coerceIn(0, waveformHistory.size - 1)]
                        val wavePhase = kotlin.math.sin((nowMs / 85.0) + (i * 0.7)).toFloat() * 0.22f * currentNormalizedAmplitude
                        val effectiveAmp = (histSample + wavePhase).coerceIn(0.12f, 1.0f)
                        val barH = (minBarH + (maxBarH - minBarH) * effectiveAmp * envelope).coerceIn(minBarH, maxBarH)
                        val bx = cursorX + i * barSpacing
                        iconPaint.color = Color.parseColor("#E8473A")
                        iconPaint.strokeWidth = 2.0f * density
                        canvas.drawLine(bx, (h - barH) / 2f, bx, (h + barH) / 2f, iconPaint)
                    }
                    cursorX += waveformTotalW + gap

                    // 4. Status Label ('Listening' / 'শুনছি') in Muted Ivory
                    textPaint.color = Color.parseColor("#B8BDC6")
                    canvas.drawText(statusStr, cursorX, textY, textPaint)

                    // Keep animating at 60fps during RECORDING!
                    postInvalidateOnAnimation()
                }

                StripState.PROCESSING -> {
                    val innerGap = 3.5f * density
                    val sideBtnW = 36f * density
                    cancelBounds.set(
                        pillRect.left + innerGap,
                        pillRect.top + innerGap,
                        pillRect.left + innerGap + sideBtnW,
                        pillRect.bottom - innerGap
                    )
                    val btnRadius = cancelBounds.height() / 2f

                    // Left Cancel (✕) Button
                    bgPaint.color = Color.parseColor("#282C33")
                    canvas.drawRoundRect(cancelBounds, btnRadius, btnRadius, bgPaint)
                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 13f * density
                    val xW = textPaint.measureText("✕")
                    canvas.drawText("✕", cancelBounds.centerX() - xW / 2f, textY, textPaint)

                    // Mathematically Centered [ ⟳  Formatting... ] in the full bar
                    val procStr = if (isBangla) "সাজানো হচ্ছে..." else "Formatting..."
                    textPaint.textSize = 12.5f * density
                    val procW = textPaint.measureText(procStr)
                    val spinnerRadius = 6.5f * density
                    val totalW = (spinnerRadius * 2f) + (8f * density) + procW
                    val startX = (w - totalW) / 2f

                    // Rotating Ochre Arc Spinner
                    val sweepAngle = 240f
                    val rotAngle = ((System.currentTimeMillis() % 900L) / 900f) * 360f
                    iconPaint.color = Color.parseColor("#E3B257")
                    iconPaint.strokeWidth = 2f * density
                    val arcRect = RectF(
                        startX,
                        h / 2f - spinnerRadius,
                        startX + spinnerRadius * 2f,
                        h / 2f + spinnerRadius
                    )
                    canvas.drawArc(arcRect, rotAngle, sweepAngle, false, iconPaint)

                    textPaint.color = Color.parseColor("#EEEBE3")
                    canvas.drawText(procStr, startX + spinnerRadius * 2f + 8f * density, textY, textPaint)

                    // Trigger next frame for smooth 60fps spinner rotation during PROCESSING!
                    postInvalidateOnAnimation()
                }

                StripState.INSERTED -> {
                    textPaint.color = Color.parseColor("#0D0E10")
                    textPaint.textSize = 12.5f * density
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
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                when (currentState) {
                    StripState.IDLE -> {
                        // Tapping idle mic capsule immediately expands and starts recording!
                        setState(StripState.RECORDING)
                        onStartRecording()
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
