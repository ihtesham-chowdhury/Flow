package com.flowbangla.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.flowbangla.app.model.TranscriptionState

/**
 * Ink & Danda Floating Mic Canvas View.
 *
 * Implements the fixed-palette overlay mic described in FLOW_PREMIUM_REDESIGN_GUIDE.md §7:
 * - Idle: Ivory (#EEEBE3) disc, obsidian (#0D0E10) danda-mic glyph, 1dp 20% alpha keyline.
 * - Recording: Vermilion (#E8473A) disc, ivory glyph, continuous vermilion voice ring breathing with amplitude.
 * - Processing: Ivory disc at 80%, dark glyph at 60%, 90° ochre (#E3B257) orbiting arc.
 * - Inserted: Sage (#8FBF9F) disc fill for 280ms, returns to idle over 200ms.
 * - Error: Ivory disc, dark glyph, dashed muted ring (#9EA3AB).
 * - Language chip: 22dp circle at bottom-right of disc with #0D0E10 background and ivory text ("EN" / "বাং").
 */
class FloatingMicCanvasView(context: Context) : View(context) {

    // Fixed Overlay Tokens per Spec §7.1
    private val colorDiscIdle = 0xFFEEEBE3.toInt()       // Ivory
    private val colorGlyphIdle = 0xFF0D0E10.toInt()      // Obsidian dark
    private val colorKeyline = 0x330D0E10.toInt()        // 20% alpha keyline
    private val colorDiscRecording = 0xFFE8473A.toInt()  // Vermilion
    private val colorGlyphRecording = 0xFFEEEBE3.toInt() // Ivory
    private val colorArcProcessing = 0xFFE3B257.toInt()  // Ochre
    private val colorSuccessSage = 0xFF8FBF9F.toInt()    // Sage
    private val colorMutedRing = 0xFF9EA3AB.toInt()      // DarkInkMuted

    // Paints
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val keylinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorKeyline
    }

    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val voiceRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(178, 232, 71, 58) // 70% vermilion
    }

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorArcProcessing
    }

    private val dashedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorMutedRing
    }

    // Language Chip Paints (§7.5)
    private var activeLanguageBadge: String? = null
    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0D0E10.toInt()
    }
    private val chipKeylinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF0D0E10.toInt()
    }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEBE3.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Gestures & Callbacks
    var onSingleTapAction: (() -> Unit)? = null
    var onDoubleTapLanguageSwitch: (() -> Unit)? = null

    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapAction?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (onDoubleTapLanguageSwitch != null) {
                onDoubleTapLanguageSwitch?.invoke()
                return true
            }
            return false
        }
    })

    // Animation States
    private var currentState: TranscriptionState = TranscriptionState.Idle
    private var voiceAmplitude: Float = 0f
    private var smoothedAmplitude: Float = 0f
    private var spinnerRotation: Float = 0f
    private var spinnerAnimator: ValueAnimator? = null

    // Inserted transient animation
    private var isShowingSuccessAnimation = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val successResetRunnable = Runnable {
        isShowingSuccessAnimation = false
        invalidate()
    }

    init {
        val density = resources.displayMetrics.density
        keylinePaint.strokeWidth = 1f * density
        voiceRingPaint.strokeWidth = 2f * density
        spinnerPaint.strokeWidth = 2.5f * density
        dashedRingPaint.strokeWidth = 1.5f * density
        dashedRingPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        chipKeylinePaint.strokeWidth = 2f * density
    }

    fun setLanguageBadge(badge: String?) {
        if (activeLanguageBadge != badge) {
            activeLanguageBadge = badge
            invalidate()
        }
    }

    fun setState(state: TranscriptionState) {
        val oldState = currentState
        currentState = state

        if (state is TranscriptionState.Success) {
            isShowingSuccessAnimation = true
            mainHandler.removeCallbacks(successResetRunnable)
            mainHandler.postDelayed(successResetRunnable, 480L) // 280ms full + 200ms ease
        } else if (oldState is TranscriptionState.Success && state is TranscriptionState.Idle && isShowingSuccessAnimation) {
            // Keep transient success animation active until timer expires
        } else {
            isShowingSuccessAnimation = false
            mainHandler.removeCallbacks(successResetRunnable)
        }

        if (state is TranscriptionState.Processing) {
            startSpinner()
        } else {
            stopSpinner()
        }
        invalidate()
    }

    fun setVoiceAmplitude(amplitude: Float) {
        voiceAmplitude = amplitude.coerceIn(0f, 1f)
        // 60ms smoothing per §3.4
        smoothedAmplitude = (smoothedAmplitude * 0.6f) + (voiceAmplitude * 0.4f)
        if (currentState is TranscriptionState.Recording) {
            invalidate()
        }
    }

    fun updateAmplitude(amplitude: Float) {
        setVoiceAmplitude(amplitude)
    }

    private fun startSpinner() {
        if (spinnerAnimator?.isRunning == true) return
        spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100L // 1100ms per revolution linear per §7.1
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinnerRotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpinner() {
        spinnerAnimator?.cancel()
        spinnerAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density

        // Disc radius fills ~82% of view to leave room for voice breathing ring and language chip
        val discRadius = (w.coerceAtMost(h) / 2f) * 0.78f
        val u = discRadius / 28f // 1 unit = 1/28 of disc radius per §4.4

        val isRecording = currentState is TranscriptionState.Recording
        val isProcessing = currentState is TranscriptionState.Processing
        val isSuccess = isShowingSuccessAnimation || currentState is TranscriptionState.Success
        val isError = currentState is TranscriptionState.Error

        // 1. Voice Ring (Recording only per §7.1)
        if (isRecording) {
            val ringRadius = discRadius + (4f * density) + (smoothedAmplitude * 4f * density)
            canvas.drawCircle(cx, cy, ringRadius, voiceRingPaint)
        }

        // 2. Dashed Ring (Error only per §7.1)
        if (isError) {
            val errRadius = discRadius + (2f * density)
            canvas.drawCircle(cx, cy, errRadius, dashedRingPaint)
        }

        // 3. Processing Orbiting Arc (Processing only per §7.1)
        if (isProcessing) {
            val arcRadius = discRadius + (2f * density)
            val arcRect = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            canvas.save()
            canvas.rotate(spinnerRotation, cx, cy)
            // 90° ochre arc orbits the ring per §7.1
            canvas.drawArc(arcRect, 0f, 90f, false, spinnerPaint)
            canvas.restore()
        }

        // 4. Main Disc Fill
        val (discColor, discAlpha) = when {
            isSuccess -> Pair(colorSuccessSage, 255)
            isRecording -> Pair(colorDiscRecording, 255)
            isProcessing -> Pair(colorDiscIdle, (255 * 0.80f).toInt())
            else -> Pair(colorDiscIdle, 255)
        }
        discPaint.color = discColor
        discPaint.alpha = discAlpha
        canvas.drawCircle(cx, cy, discRadius, discPaint)

        // 5. Keyline around disc (keeps ivory disc visible on light apps per §7.1)
        if (!isRecording && !isSuccess) {
            canvas.drawCircle(cx, cy, discRadius - (keylinePaint.strokeWidth / 2f), keylinePaint)
        }

        // 6. Center Glyph: Danda-Mic or Checkmark
        val (glyphColor, glyphAlpha) = when {
            isRecording -> Pair(colorGlyphRecording, 255)
            isSuccess -> Pair(colorGlyphIdle, 255)
            isProcessing -> Pair(colorGlyphIdle, (255 * 0.60f).toInt())
            else -> Pair(colorGlyphIdle, 255)
        }
        glyphFillPaint.color = glyphColor
        glyphFillPaint.alpha = glyphAlpha
        glyphStrokePaint.color = glyphColor
        glyphStrokePaint.alpha = glyphAlpha

        if (isSuccess) {
            drawCheckmark(canvas, cx, cy, discRadius * 0.5f, glyphStrokePaint)
        } else {
            drawDandaMicGeometry(canvas, cx, cy, u, glyphFillPaint, glyphStrokePaint)
        }

        // 7. Language Chip (§7.5: 22dp circle at bottom-right, overlapping by 6dp)
        activeLanguageBadge?.let { badge ->
            val chipRadiusPx = 11f * density // 22dp diameter
            val chipCx = cx + discRadius - (6f * density)
            val chipCy = cy + discRadius - (6f * density)

            // 2dp dark keyline so it cleanly separates from disc
            canvas.drawCircle(chipCx, chipCy, chipRadiusPx + (1f * density), chipKeylinePaint)
            // Chip background
            canvas.drawCircle(chipCx, chipCy, chipRadiusPx, chipBgPaint)

            // Chip text ("EN" / "বাং")
            chipTextPaint.textSize = 11f * density
            val textOffset = (chipTextPaint.descent() + chipTextPaint.ascent()) / 2f
            canvas.drawText(badge, chipCx, chipCy - textOffset, chipTextPaint)
        }
    }

    /**
     * Exact Danda-Mic Drawing according to Design Spec §4.4:
     * - Disc radius = 28 units (u = radius / 28)
     * - Capsule: 6u wide x 14u tall, rounded fully, top at (cx - 3u, cy - 12u)
     * - Cradle: half-circle arc under capsule, center (cx, cy + 2u), radius 7u
     * - Stem: vertical danda stroke from cy + 9u to cy + 13u
     */
    private fun drawDandaMicGeometry(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        u: Float,
        fillPaint: Paint,
        strokePaint: Paint
    ) {
        strokePaint.strokeWidth = 1.6f * u

        // 1. Capsule (mic head)
        val capsuleRect = RectF(
            cx - (3f * u),
            cy - (12f * u),
            cx + (3f * u),
            cy + (2f * u)
        )
        canvas.drawRoundRect(capsuleRect, 3f * u, 3f * u, fillPaint)

        // 2. Cradle (half-circle arc)
        val cradleRect = RectF(
            cx - (7f * u),
            cy - (5f * u),
            cx + (7f * u),
            cy + (9f * u)
        )
        canvas.drawArc(cradleRect, 0f, 180f, false, strokePaint)

        // 3. Stem (the vertical danda)
        canvas.drawLine(cx, cy + (9f * u), cx, cy + (13f * u), strokePaint)
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val density = resources.displayMetrics.density
        paint.strokeWidth = 2.8f * density
        val path = Path().apply {
            moveTo(cx - (size * 0.45f), cy)
            lineTo(cx - (size * 0.12f), cy + (size * 0.35f))
            lineTo(cx + (size * 0.50f), cy - (size * 0.35f))
        }
        canvas.drawPath(path, paint)
    }
}
