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
import com.flowbangla.app.autofill.FlowInlineAutofillService
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.model.SttLanguage
import kotlin.math.abs

typealias ChipState = KeyboardStripOverlayController.StripState

/**
 * Renders Flow's voice trigger and recording controls DIRECTLY INSIDE Gboard / Samsung Keyboard's
 * 44dp top suggestion strip (at `y = keyboardTopY + 4dp`), NEVER above the keyboard!
 *
 * - In IDLE state: A compact 68dp x 36dp pill (`[ 🎙️ বাং ]`) sitting cleanly inside the right side
 *   of the suggestion strip (just left of Gboard's mic icon), leaving Gboard suggestions & icons open!
 *   (Or hidden in IDLE when the native Autofill inline chip is already displayed in the strip).
 * - In RECORDING / PROCESSING state: Expands horizontally INSIDE the suggestion strip
 *   (`x = 50dp`, `width = screenWidth - 100dp`, `height = 36dp`, `y = keyboardTopY + 4dp`)
 *   showing `[ ✕ ]  🔴 শুনছি • বলুন...  [ ✓ ]` right inside Gboard's top bar!
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
            val compactW = (68f * density).toInt()
            val stripH = (36f * density).toInt()

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
            }

            val view = InStripChipView(a11yService)
            windowManager.addView(view, params)
            stripView = view
            layoutParams = params
            isAdded = true
            view.visibility = View.GONE
        } catch (_: Throwable) {}
    }

    fun updateKeyboardVisibilityAndPosition(isKeyboardOpen: Boolean, keyboardTopY: Int) {
        if (!isAdded) attachIfNeeded()
        isKeyboardCurrentlyOpen = isKeyboardOpen
        if (keyboardTopY > 0) {
            lastKnownKeyboardTopY = keyboardTopY
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
                }, 260L)
            }
        }
    }

    /**
     * Positions the chip STRICTLY INSIDE Gboard's top suggestion strip:
     * `y = keyboardTopY + 4dp` (NEVER `keyboardTopY - 42dp`!).
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

        // If the native Android 11+ Autofill InlineSuggestion chip is currently displayed inside Gboard,
        // hide our overlay during IDLE so there is only ONE chip in the strip, and show our overlay
        // the instant the user taps it to enter RECORDING / PROCESSING!
        if (state == StripState.IDLE && FlowInlineAutofillService.isNativeInlineChipActiveOnCurrentField) {
            view.visibility = View.GONE
            return
        }

        val metrics = a11yService.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val kbdTop = if (lastKnownKeyboardTopY > 0) lastKnownKeyboardTopY else (metrics.heightPixels * 0.60f).toInt()

        // CRITICAL GEOMETRY FIX:
        // Place at `kbdTop + 4dp` so it sits INSIDE Gboard's 44dp suggestion strip!
        val targetY = kbdTop + (4f * density).toInt()
        val targetH = (36f * density).toInt()

        val targetW: Int
        val targetX: Int

        if (state == StripState.IDLE) {
            // Compact 68dp mini-button tucked neatly just to the left of Gboard's right-hand mic icon (48dp)
            targetW = (68f * density).toInt()
            targetX = screenW - targetW - (48f * density).toInt()
        } else {
            // Expanded in-strip recording pod between Gboard's left [⊞] icon (50dp) and right [🎤] icon (50dp)
            targetX = (50f * density).toInt()
            targetW = (screenW - (100f * density).toInt()).coerceAtLeast((180f * density).toInt())
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

        private val density = resources.displayMetrics.density
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3A3F47")
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEBE3")
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEBE3")
            textSize = 12.5f * density
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val pillRect = RectF()
        private val cancelBounds = RectF()
        private val confirmBounds = RectF()
        private var lastTapTimeMs = 0L

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 1.5f * density
            pillRect.set(pad, pad, w - pad, h - pad)
            val r = h / 2f

            // Match Gboard Dark Suggestion Chip surface (#25282D) or Sage on Insert
            bgPaint.color = when (currentState) {
                StripState.INSERTED -> Color.parseColor("#8FBF9F")
                StripState.RECORDING -> Color.parseColor("#1A1C20")
                else -> Color.parseColor("#25282D")
            }
            canvas.drawRoundRect(pillRect, r, r, bgPaint)
            canvas.drawRoundRect(pillRect, r, r, borderPaint)

            val isBangla = prefs.sttLanguage == SttLanguage.BENGALI
            val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

            when (currentState) {
                StripState.IDLE -> {
                    // Compact Mini-Chip: [ 🎙️ বাং ] (Only 68dp wide!)
                    drawMiniDandaMicIcon(canvas, cx = 20f * density, cy = h / 2f, color = Color.parseColor("#EEEBE3"))
                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 12f * density
                    val lang = if (isBangla) "বাং" else "EN"
                    canvas.drawText(lang, 34f * density, textY, textPaint)
                }

                StripState.RECORDING -> {
                    // Expanded inside Gboard strip: [ ✕ ]   🔴 বলুন / Speak (বাং)   [ ✓ ]
                    val btnW = 42f * density
                    cancelBounds.set(pad + 2f * density, pad + 2f * density, pad + btnW, h - pad - 2f * density)
                    confirmBounds.set(w - pad - btnW, pad + 2f * density, w - pad - 2f * density, h - pad - 2f * density)

                    // Left Cancel Circle/Pill
                    bgPaint.color = Color.parseColor("#2E3238")
                    canvas.drawRoundRect(cancelBounds, r, r, bgPaint)
                    textPaint.color = Color.parseColor("#EEEBE3")
                    textPaint.textSize = 13f * density
                    val xW = textPaint.measureText("✕")
                    canvas.drawText("✕", cancelBounds.centerX() - xW / 2f, textY, textPaint)

                    // Center Live Recording Dot + Text
                    bgPaint.color = Color.parseColor("#E8473A") // Vermilion recording dot
                    val centerText = if (isBangla) "শুনছি • বলুন..." else "Listening..."
                    val ctW = textPaint.measureText(centerText)
                    val centerStart = (w - ctW) / 2f
                    canvas.drawCircle(centerStart - 10f * density, h / 2f, 4f * density, bgPaint)
                    canvas.drawText(centerText, centerStart, textY, textPaint)

                    // Right Confirm [✓] Pill in Sage
                    bgPaint.color = Color.parseColor("#8FBF9F")
                    canvas.drawRoundRect(confirmBounds, r, r, bgPaint)
                    textPaint.color = Color.parseColor("#0D0E10")
                    val tickW = textPaint.measureText("✓")
                    canvas.drawText("✓", confirmBounds.centerX() - tickW / 2f, textY, textPaint)
                }

                StripState.PROCESSING -> {
                    val btnW = 42f * density
                    cancelBounds.set(pad + 2f * density, pad + 2f * density, pad + btnW, h - pad - 2f * density)
                    bgPaint.color = Color.parseColor("#2E3238")
                    canvas.drawRoundRect(cancelBounds, r, r, bgPaint)
                    textPaint.color = Color.parseColor("#EEEBE3")
                    val xW = textPaint.measureText("✕")
                    canvas.drawText("✕", cancelBounds.centerX() - xW / 2f, textY, textPaint)

                    // Ochre processing indicator
                    bgPaint.color = Color.parseColor("#E3B257")
                    val procText = if (isBangla) "সাজানো হচ্ছে..." else "Formatting..."
                    val ptW = textPaint.measureText(procText)
                    val centerStart = (w - ptW) / 2f + 10f * density
                    canvas.drawCircle(centerStart - 10f * density, h / 2f, 4f * density, bgPaint)
                    canvas.drawText(procText, centerStart, textY, textPaint)
                }

                StripState.INSERTED -> {
                    textPaint.color = Color.parseColor("#0D0E10")
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
                        val now = System.currentTimeMillis()
                        if (now - lastTapTimeMs < 320L) {
                            // Double-tap toggles language (বাং ↔ EN)
                            onToggleLanguage()
                            invalidate()
                        } else {
                            // Single-tap expands inside the Gboard strip and starts recording!
                            onStartRecording()
                        }
                        lastTapTimeMs = now
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
