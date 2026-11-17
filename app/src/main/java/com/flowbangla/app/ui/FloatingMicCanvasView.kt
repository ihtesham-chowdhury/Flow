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
import com.flowbangla.app.FlowBanglaApp
import com.flowbangla.app.model.TranscriptionState

enum class PodHitZone {
    MAIN_OR_CONFIRM, // ✓ Tick button (or Idle Mic button)
    CANCEL,          // ✕ Cross button
    NONE
}

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

    // Live Recording Animation Paints (§7.1 + Dynamic Feedback)
    private val recordingArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFE8473A.toInt() // Vibrant Vermilion
    }

    private val recordingTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(60, 232, 71, 58) // 25% Vermilion track
    }

    private val recordingAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 232, 71, 58) // Soft breathing aura
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

    // Action Pod Paints (Confirm ✓ / Cancel ✕ Capsule Pod)
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb((255 * 0.96f).toInt(), 0x16, 0x18, 0x1B) // #F516181B
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2A2E33.toInt() // 1dp hairline
    }
    private val cancelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1F2226.toInt() // Raised Obsidian #1F2226
    }
    private val cancelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2A2E33.toInt()
    }
    private val cancelGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFEEEBE3.toInt() // Ivory #EEEBE3
    }
    private val confirmBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorSuccessSage // Sage #8FBF9F
    }
    private val confirmGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF0D0E10.toInt() // Dark Ink #0D0E10
    }
    private val waveformBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorDiscRecording // Vermilion #E8473A
    }

    // Hit-testing bounds
    val cancelCircleBounds = RectF()
    val confirmCircleBounds = RectF()
    var isDockedOnRight: Boolean = true

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
    private var recordingRingRotation: Float = 0f
    private var recordingRingAnimator: ValueAnimator? = null

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
        recordingArcPaint.strokeWidth = 2.5f * density
        recordingTrackPaint.strokeWidth = 1.5f * density
        spinnerPaint.strokeWidth = 2.5f * density
        dashedRingPaint.strokeWidth = 1.5f * density
        dashedRingPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        chipKeylinePaint.strokeWidth = 2f * density
        pillBorderPaint.strokeWidth = 1f * density
        cancelBorderPaint.strokeWidth = 1.5f * density
        cancelGlyphPaint.strokeWidth = 2.2f * density
        confirmGlyphPaint.strokeWidth = 2.6f * density
        waveformBarPaint.strokeWidth = 3f * density
    }

    fun resolveHitZone(touchX: Float, touchY: Float): PodHitZone {
        val density = resources.displayMetrics.density
        val slop = 12f * density
        return ActionPodHitResolver.resolve(
            touchX = touchX,
            touchY = touchY,
            isRecording = currentState is TranscriptionState.Recording,
            isProcessing = currentState is TranscriptionState.Processing,
            cancelLeft = cancelCircleBounds.left,
            cancelRight = cancelCircleBounds.right,
            cancelTop = cancelCircleBounds.top,
            cancelBottom = cancelCircleBounds.bottom,
            confirmLeft = confirmCircleBounds.left,
            confirmRight = confirmCircleBounds.right,
            confirmTop = confirmCircleBounds.top,
            confirmBottom = confirmCircleBounds.bottom,
            slop = slop
        )
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

        if (state is TranscriptionState.Recording) {
            startRecordingAnimation()
        } else {
            stopRecordingAnimation()
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

    private fun startRecordingAnimation() {
        if (recordingRingAnimator?.isRunning == true) return
        recordingRingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1350L // 1350ms per revolution linear rotation
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                recordingRingRotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopRecordingAnimation() {
        recordingRingAnimator?.cancel()
        recordingRingAnimator = null
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRecordingAnimation()
        stopSpinner()
        mainHandler.removeCallbacks(successResetRunnable)
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

        if (isRecording) {
            // Draw Expanded Recording Action Pod
            val pillRadius = h / 2f
            canvas.drawRoundRect(0f, 0f, w, h, pillRadius, pillRadius, pillBgPaint)
            canvas.drawRoundRect(0f, 0f, w, h, pillRadius, pillRadius, pillBorderPaint)

            // Cancel Button (✕) on Left
            val cancelRadius = 21f * density
            val cancelCx = 26f * density
            cancelCircleBounds.set(cancelCx - cancelRadius, cy - cancelRadius, cancelCx + cancelRadius, cy + cancelRadius)
            canvas.drawCircle(cancelCx, cy, cancelRadius, cancelBgPaint)
            canvas.drawCircle(cancelCx, cy, cancelRadius, cancelBorderPaint)
            drawCross(canvas, cancelCx, cy, 11f * density, cancelGlyphPaint)

            // Confirm Button (✓) with 60fps rotating ring on Right
            val confirmRadius = 22f * density
            val confirmCx = w - (27f * density)
            confirmCircleBounds.set(confirmCx - confirmRadius, cy - confirmRadius, confirmCx + confirmRadius, cy + confirmRadius)

            val arcRadius = confirmRadius + (3.5f * density)
            val arcRect = RectF(confirmCx - arcRadius, cy - arcRadius, confirmCx + arcRadius, cy + arcRadius)
            canvas.drawCircle(confirmCx, cy, arcRadius, recordingTrackPaint)
            canvas.save()
            canvas.rotate(recordingRingRotation, confirmCx, cy)
            canvas.drawArc(arcRect, 0f, 95f, false, recordingArcPaint)
            canvas.drawArc(arcRect, 180f, 95f, false, recordingArcPaint)
            canvas.restore()

            try {
                val accent = when (FlowBanglaApp.instance.preferencesManager.accentColorKey) {
                    "ochre" -> 0xFFE3B257.toInt()
                    "sage" -> 0xFF8FBF9F.toInt()
                    "sky" -> 0xFF38BDF8.toInt()
                    "rose" -> 0xFFFB7185.toInt()
                    "violet" -> 0xFFA78BFA.toInt()
                    else -> colorSuccessSage
                }
                confirmBgPaint.color = accent
            } catch (_: Exception) {
                confirmBgPaint.color = colorSuccessSage
            }
            canvas.drawCircle(confirmCx, cy, confirmRadius, confirmBgPaint)
            drawCheckmark(canvas, confirmCx, cy, confirmRadius * 0.72f, confirmGlyphPaint)

            // Center: Live audio amplitude waveform bars
            val centerStartX = cancelCx + cancelRadius + (8f * density)
            val centerEndX = confirmCx - confirmRadius - (8f * density)
            val barSpacing = 7f * density
            val numBars = 4
            val totalWidth = (numBars - 1) * barSpacing
            val startX = (centerStartX + centerEndX) / 2f - (totalWidth / 2f)
            val barHeights = floatArrayOf(6f, 15f, 12f, 7f)
            for (i in 0 until numBars) {
                val bx = startX + (i * barSpacing)
                val bh = (barHeights[i] * density) * (0.35f + smoothedAmplitude * 0.95f)
                canvas.drawLine(bx, cy - bh, bx, cy + bh, waveformBarPaint)
            }
            return
        }

        if (isProcessing) {
            // Draw Expanded Processing Action Pod
            val pillRadius = h / 2f
            canvas.drawRoundRect(0f, 0f, w, h, pillRadius, pillRadius, pillBgPaint)
            canvas.drawRoundRect(0f, 0f, w, h, pillRadius, pillRadius, pillBorderPaint)

            // Cancel Button (✕) on Left
            val cancelRadius = 21f * density
            val cancelCx = 26f * density
            cancelCircleBounds.set(cancelCx - cancelRadius, cy - cancelRadius, cancelCx + cancelRadius, cy + cancelRadius)
            canvas.drawCircle(cancelCx, cy, cancelRadius, cancelBgPaint)
            canvas.drawCircle(cancelCx, cy, cancelRadius, cancelBorderPaint)
            drawCross(canvas, cancelCx, cy, 11f * density, cancelGlyphPaint)
            confirmCircleBounds.setEmpty()

            // Right: Orbiting 90° Ochre spinner
            val spinnerCx = w - (26f * density)
            val spinnerRadius = 18f * density
            val arcRect = RectF(spinnerCx - spinnerRadius, cy - spinnerRadius, spinnerCx + spinnerRadius, cy + spinnerRadius)
            canvas.save()
            canvas.rotate(spinnerRotation, spinnerCx, cy)
            canvas.drawArc(arcRect, 0f, 90f, false, spinnerPaint)
            canvas.restore()
            return
        }

        cancelCircleBounds.setEmpty()
        confirmCircleBounds.set(0f, 0f, w, h)

        // 1. Live Recording Active Ring + Dynamic Voice Aura (Recording only)
        if (isRecording) {
            // Soft breathing outer aura scaling with voice amplitude
            val auraRadius = discRadius + (4.5f * density) + (smoothedAmplitude * 6.5f * density)
            canvas.drawCircle(cx, cy, auraRadius, recordingAuraPaint)

            // Dynamic voice breathing track
            val voiceRingRadius = discRadius + (3.5f * density) + (smoothedAmplitude * 4.5f * density)
            canvas.drawCircle(cx, cy, voiceRingRadius, recordingTrackPaint)

            // Live 60fps continuous rotating recording ring (Dual orbiting active arcs)
            val arcRect = RectF(
                cx - voiceRingRadius,
                cy - voiceRingRadius,
                cx + voiceRingRadius,
                cy + voiceRingRadius
            )
            canvas.save()
            canvas.rotate(recordingRingRotation, cx, cy)
            canvas.drawArc(arcRect, 0f, 95f, false, recordingArcPaint)
            canvas.drawArc(arcRect, 180f, 95f, false, recordingArcPaint)
            canvas.restore()
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

        // 4. Main Disc & Microphone Rendering
        if (isSuccess) {
            discPaint.color = colorSuccessSage
            discPaint.alpha = 255
            canvas.drawCircle(cx, cy, discRadius, discPaint)
            glyphStrokePaint.color = colorGlyphIdle
            glyphStrokePaint.alpha = 255
            drawCheckmark(canvas, cx, cy, discRadius * 0.5f, glyphStrokePaint)
        } else {
            // Flow v4.3.0 Flagship Hardware Idle Mic: Obsidian & Brushed Titanium Danda-Jewel
            drawLuxuryIdleMicButton(canvas, cx, cy, discRadius)
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
     * Flow v4.3.0 Senior Industrial Design Spec:
     * The "Obsidian & Brushed Titanium Danda-Jewel" Idle Floating Button.
     * Four tactile depth layers:
     * 1. Ambient drop shadow (elevation depth)
     * 2. Machined anodized titanium outer bezel ring (linear gradient #545B68 -> #14161B)
     * 3. Recessed obsidian velvet well (radial gradient #242832 -> #0B0D10) with 0.9dp hairline rim
     * 4. Sculpted champagne-ivory condenser mic (#FAF7F0 -> #CFC8B8) with 2 horizontal grille slits,
     *    precision 2.0u U-cradle, vertical Danda stem (।), pedestal base, and a 2 o'clock Vermilion status jewel!
     */
    private fun drawLuxuryIdleMicButton(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val d = resources.displayMetrics.density

        // 1. Soft Ambient Elevation Shadow
        val outerDiscRadius = radius - 3f * d
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 0, 0, 0)
            maskFilter = BlurMaskFilter(4.5f * d, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy + 2f * d, outerDiscRadius, shadowPaint)

        // 2. Machined Anodized Titanium Outer Bezel Ring (Top-Left Specular Highlight -> Bottom-Right Shadow)
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - outerDiscRadius, cy - outerDiscRadius,
                cx + outerDiscRadius, cy + outerDiscRadius,
                intArrayOf(
                    Color.parseColor("#545B68"), // Top-lit brushed titanium highlight
                    Color.parseColor("#2A2E37"), // Mid-tone
                    Color.parseColor("#14161B")  // Deep bottom chamfer
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, outerDiscRadius, bezelPaint)

        // 3. Inner Recessed Obsidian Velvet Well (Radial Gradient)
        val innerWellRadius = outerDiscRadius - 2.2f * d
        val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - innerWellRadius * 0.2f,
                cy - innerWellRadius * 0.25f,
                innerWellRadius * 1.15f,
                intArrayOf(
                    Color.parseColor("#242832"), // Subtle top-left studio sheen
                    Color.parseColor("#14171D"), // Rich obsidian body
                    Color.parseColor("#0B0D10")  // Deep perimeter well shadow
                ),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, innerWellRadius, wellPaint)

        // 4. Inner Specular Hairline Rim (0.9dp)
        val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * d
            color = Color.argb(45, 238, 235, 227)
        }
        canvas.drawCircle(cx, cy, innerWellRadius - 0.5f * d, innerRimPaint)

        // 5. Sculpted Champagne-Ivory Condenser Microphone Glyph
        val u = innerWellRadius / 22f // Precision proportional unit

        // 5a. Condenser Capsule Head with Metallic Vertical Gradient
        val capWidth = 7.6f * u
        val capHeight = 13.5f * u
        val capTop = cy - 9.2f * u
        val capRect = RectF(cx - capWidth / 2f, capTop, cx + capWidth / 2f, capTop + capHeight)
        val capRadius = capWidth / 2f

        val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx, capRect.top,
                cx, capRect.bottom,
                Color.parseColor("#FAF7F0"), // Warm ivory crest
                Color.parseColor("#CFC8B8"), // Champagne metallic base
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(capRect, capRadius, capRadius, capsulePaint)

        // 5b. Two Precision Acoustic Grille Slits Carved into Upper Capsule
        val slitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#181B22")
            style = Paint.Style.STROKE
            strokeWidth = 1.15f * u
            strokeCap = Paint.Cap.ROUND
        }
        val slitHalfW = capWidth * 0.24f
        canvas.drawLine(cx - slitHalfW, capTop + 3.8f * u, cx + slitHalfW, capTop + 3.8f * u, slitPaint)
        canvas.drawLine(cx - slitHalfW, capTop + 6.4f * u, cx + slitHalfW, capTop + 6.4f * u, slitPaint)

        // 5c. U-Cradle Arc, Vertical Danda Stem (।) & Weighted Pedestal Base
        val cradlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEBE3")
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * u
            strokeCap = Paint.Cap.ROUND
        }
        val cradleRadius = 6.8f * u
        val cradleCenterY = capRect.bottom - capRadius
        val cradleRect = RectF(
            cx - cradleRadius,
            cradleCenterY - cradleRadius,
            cx + cradleRadius,
            cradleCenterY + cradleRadius
        )
        canvas.drawArc(cradleRect, 0f, 180f, false, cradlePaint)

        // Vertical Danda Stem
        val stemTop = cradleRect.bottom
        val stemBottom = cy + 8.6f * u
        canvas.drawLine(cx, stemTop, cx, stemBottom, cradlePaint)

        // Pedestal Base Bar
        val baseHalfW = 3.8f * u
        canvas.drawLine(cx - baseHalfW, stemBottom, cx + baseHalfW, stemBottom, cradlePaint)

        // 6. Vermilion red dot removed in v4.4.0 — pure sculpted Champagne-Ivory condenser mic
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

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val half = size / 2f
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint)
        canvas.drawLine(cx - half, cy + half, cx + half, cy - half, paint)
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
