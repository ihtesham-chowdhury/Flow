package com.flowbangla.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.flowbangla.app.model.TranscriptionState

class FloatingMicCanvasView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.WHITE
    }

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val micStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    // Badge paints for language pill (বাং / EN)
    private var activeLanguageBadge: String? = null // e.g., "বাং" or "EN"

    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

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

    private var currentState: TranscriptionState = TranscriptionState.Idle
    private var voiceAmplitude: Float = 0f
    private var spinnerRotation: Float = 0f
    private var spinnerAnimator: ValueAnimator? = null

    init {
        val density = resources.displayMetrics.density
        borderPaint.strokeWidth = 2.5f * density
        micStrokePaint.strokeWidth = 2.5f * density
        spinnerPaint.strokeWidth = 3f * density
    }

    fun setLanguageBadge(badge: String?) {
        if (activeLanguageBadge != badge) {
            activeLanguageBadge = badge
            invalidate()
        }
    }

    fun setState(state: TranscriptionState) {
        currentState = state
        if (state is TranscriptionState.Processing) {
            startSpinner()
        } else {
            stopSpinner()
        }
        invalidate()
    }

    fun setVoiceAmplitude(amplitude: Float) {
        voiceAmplitude = amplitude.coerceIn(0f, 1f)
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
            duration = 900
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

        val centerX = w / 2f
        val centerY = h / 2f
        val baseRadius = (w.coerceAtMost(h) / 2f) * 0.82f

        // Draw active pulsing aura when recording
        if (currentState is TranscriptionState.Recording) {
            val auraRadius = baseRadius + (baseRadius * 0.22f * voiceAmplitude)
            auraPaint.color = Color.argb((60 + (80 * voiceAmplitude)).toInt(), 220, 38, 38)
            canvas.drawCircle(centerX, centerY, auraRadius, auraPaint)
        }

        // Draw main inner circle
        val mainColor = when (currentState) {
            is TranscriptionState.Idle -> 0xFF1E3A8A.toInt()      // Deep royal blue
            is TranscriptionState.Recording -> 0xFFDC2626.toInt() // Vibrant red
            is TranscriptionState.Processing -> 0xFFD97706.toInt()// Warm amber
            is TranscriptionState.Success -> 0xFF059669.toInt()   // Emerald green
            is TranscriptionState.Error -> 0xFFB91C1C.toInt()     // Crimson
        }
        bgPaint.color = mainColor
        canvas.drawCircle(centerX, centerY, baseRadius, bgPaint)

        // Draw crisp border
        canvas.drawCircle(centerX, centerY, baseRadius - (borderPaint.strokeWidth / 2f), borderPaint)

        // Draw Center Icon (Mic, Spinner, or Checkmark)
        when (currentState) {
            is TranscriptionState.Processing -> {
                drawSpinner(canvas, centerX, centerY, baseRadius * 0.52f)
            }
            is TranscriptionState.Success -> {
                drawCheckmark(canvas, centerX, centerY, baseRadius * 0.45f)
            }
            else -> {
                drawMicIcon(canvas, centerX, centerY, baseRadius * 0.48f)
            }
        }

        // Draw language pill badge on bottom-right rim if active
        activeLanguageBadge?.let { badge ->
            val badgeRadius = w * 0.20f
            val badgeCx = w * 0.76f
            val badgeCy = h * 0.76f

            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgeBackgroundPaint)
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgeBorderPaint)

            badgeTextPaint.textSize = badgeRadius * 0.95f
            val textOffset = (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(badge, badgeCx, badgeCy - textOffset, badgeTextPaint)
        }
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val density = resources.displayMetrics.density
        micStrokePaint.strokeWidth = 2.4f * density

        val bodyW = size * 0.44f
        val bodyH = size * 0.82f
        val top = cy - (size * 0.65f)
        val bottom = top + bodyH
        val left = cx - (bodyW / 2f)
        val right = cx + (bodyW / 2f)
        val corner = bodyW / 2f

        // 1. Microphone Capsule
        val capsuleRect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(capsuleRect, corner, corner, micPaint)

        // 2. Cradle Arc
        val cradleRadius = bodyW * 0.95f
        val cradleRect = RectF(
            cx - cradleRadius,
            top + (bodyH * 0.28f),
            cx + cradleRadius,
            bottom + (cradleRadius * 0.45f)
        )
        canvas.drawArc(cradleRect, 10f, 160f, false, micStrokePaint)

        // 3. Stem
        val stemTop = cradleRect.bottom - (cradleRadius * 0.28f)
        val stemBottom = stemTop + (size * 0.35f)
        canvas.drawLine(cx, stemTop, cx, stemBottom, micStrokePaint)

        // 4. Base foot
        val footW = size * 0.42f
        canvas.drawLine(cx - (footW / 2f), stemBottom, cx + (footW / 2f), stemBottom, micStrokePaint)
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.save()
        canvas.rotate(spinnerRotation, cx, cy)
        canvas.drawArc(rect, 0f, 270f, false, spinnerPaint)
        canvas.restore()
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val density = resources.displayMetrics.density
        micStrokePaint.strokeWidth = 3f * density

        val path = Path().apply {
            moveTo(cx - (size * 0.5f), cy)
            lineTo(cx - (size * 0.15f), cy + (size * 0.4f))
            lineTo(cx + (size * 0.55f), cy - (size * 0.35f))
        }
        canvas.drawPath(path, micStrokePaint)
    }
}
