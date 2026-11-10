package com.flowbangla.app.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.flowbangla.app.FlowBanglaApp
import com.flowbangla.app.R
import com.flowbangla.app.api.GeminiInteractionsAdapter
import com.flowbangla.app.api.GroqWhisperClient
import com.flowbangla.app.audio.AudioRecorder
import com.flowbangla.app.audio.OnDeviceSpeechRecognizer
import com.flowbangla.app.format.ActiveFieldContext
import com.flowbangla.app.format.FormattingChain
import com.flowbangla.app.format.RecognitionSource
import com.flowbangla.app.model.DictationRecord
import com.flowbangla.app.model.EngineMode
import com.flowbangla.app.model.InteractionMode
import com.flowbangla.app.model.TranscriptionState
import com.flowbangla.app.ui.FloatingMicCanvasView
import com.flowbangla.app.ui.MainActivity
import okhttp3.OkHttpClient
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleContainer: FrameLayout? = null
    private var canvasView: FloatingMicCanvasView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    // Live preview pill (optional, throttled to >=250ms with zero updateViewLayout calls)
    private var previewTextView: TextView? = null
    private var previewWindowParams: WindowManager.LayoutParams? = null
    private var lastPreviewUpdateMs = 0L

    // Messenger-style dismiss target at bottom of screen
    private var dismissTargetView: TextView? = null
    private var dismissTargetParams: WindowManager.LayoutParams? = null
    private var isNearDismissTarget = false

    @Volatile
    private var isKeyboardCurrentlyVisible: Boolean = false

    @Volatile
    var isDismissedForCurrentKeyboardSession: Boolean = false

    var isManuallyDismissed: Boolean
        get() = isDismissedForCurrentKeyboardSession
        set(value) { isDismissedForCurrentKeyboardSession = value }

    @Volatile
    var lastUndoTranscript: String? = null

    // 1-Tap Recovery Banner for father-friendly zero text loss
    private var pendingPasteBannerView: TextView? = null
    private var pendingPasteBannerParams: WindowManager.LayoutParams? = null
    private var pendingPasteBannerDismissJob: Job? = null

    // Transcription & Formatting engines
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var geminiInteractionsAdapter: GeminiInteractionsAdapter
    private lateinit var groqWhisperClient: GroqWhisperClient
    private lateinit var onDeviceRecognizer: OnDeviceSpeechRecognizer
    private lateinit var formattingChain: FormattingChain
    private lateinit var okHttpClient: OkHttpClient

    private var liveTranscribeStreamer: com.flowbangla.app.api.GeminiLiveTranscribeStreamer? = null
    private var rollingChunkTranscriber: com.flowbangla.app.audio.RollingChunkTranscriber? = null
    private var activeDictationJob: Job? = null
    private var processingWatchdogJob: Job? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var currentState: TranscriptionState = TranscriptionState.Idle
        private set

    enum class TriggerSurface { NONE, INLINE_BAR, FLOATING_MIC }
    @Volatile
    private var activeTriggerSurface: TriggerSurface = TriggerSurface.NONE
    private var recordingStartTimeMs: Long = 0L

    private var autoCheckpointJob: Job? = null
    private val AUTO_CHECKPOINT_SOFT_MS = 45_000L
    private val AUTO_CHECKPOINT_HARD_MS = 58_000L
    private var vibrator: Vibrator? = null

    // Touch and drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = 16f
    private var touchDownTime = 0L

    // Keyboard visibility state
    private var isKeyboardActive = true

    override fun onCreate() {
        super.onCreate()
        instance = this

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        initializeEngines()
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        setupFloatingBubble()
        setupDismissTargetView()
        setupLivePreviewPill()
        setupPendingPasteBanner()
        setupKeyboardListener()
        TextInjectorAccessibilityService.instance?.requestImmediateKeyboardSync()
    }

    private fun initializeEngines() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        audioRecorder = AudioRecorder(this)
        geminiInteractionsAdapter = GeminiInteractionsAdapter(okHttpClient)
        groqWhisperClient = GroqWhisperClient(okHttpClient)
        formattingChain = FormattingChain(this, prefs)

        audioRecorder.onAmplitudeChanged = { amp, _ ->
            if (currentState is TranscriptionState.Recording) {
                canvasView?.setVoiceAmplitude(amp)
            }
        }

        onDeviceRecognizer = OnDeviceSpeechRecognizer(
            context = this,
            onAmplitudeChanged = { normalizedAmp ->
                if (currentState is TranscriptionState.Recording) {
                    canvasView?.updateAmplitude(normalizedAmp)
                }
            },
            onPartialTranscript = { partialText ->
                if (prefs.isLivePreviewEnabled) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPreviewUpdateMs >= 250L) {
                        lastPreviewUpdateMs = now
                        showThrottledPreviewText(partialText)
                    }
                }
            },
            onFinalTranscript = { rawTranscript ->
                hideThrottledPreviewText()
                processOnDeviceTranscript(rawTranscript)
            },
            onErrorOccurred = { errorMsg ->
                hideThrottledPreviewText()
                handleError(errorMsg)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TextInjectorAccessibilityService.instance?.requestImmediateKeyboardSync()
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopSelf()
            ACTION_REFRESH_SETTINGS -> {
                updateDimensionsAndOpacity()
                refreshLanguageBadge()
            }
            ACTION_TOGGLE_MIC -> {
                val prefs = FlowBanglaApp.instance.preferencesManager
                prefs.isMasterSwitchEnabled = !prefs.isMasterSwitchEnabled
                isDismissedForCurrentKeyboardSession = false
                refreshBubbleVisibility()
                updateNotification()
            }
            ACTION_SHOW_MIC -> {
                forceRestoreBubbleNow()
            }
            ACTION_START_INLINE_DICTATION -> {
                startRecordingFromExternal()
            }
            ACTION_RETRY_PASTE -> {
                val pending = TextInjectorAccessibilityService.pendingDeferredText
                if (!pending.isNullOrBlank()) {
                    serviceScope.launch {
                        val outcome = TextInjectorAccessibilityService.instance?.injectTextSuspend(pending)
                        if (outcome?.status == InjectionStatus.INJECTED_PASTE || outcome?.status == InjectionStatus.INJECTED_SET_TEXT) {
                            hidePendingPasteBanner()
                            vibrateSuccess()
                        }
                    }
                }
            }
            ACTION_UNDO_AI_EDIT -> {
                val raw = lastUndoTranscript
                if (!raw.isNullOrBlank()) {
                    serviceScope.launch {
                        val outcome = TextInjectorAccessibilityService.instance?.injectTextSuspend(raw)
                        vibrateSuccess()
                        Toast.makeText(this@FloatingBubbleService, "Raw transcript restored / মূল কথা ফিরিয়ে আনা হয়েছে", Toast.LENGTH_SHORT).show()
                        lastUndoTranscript = null
                        updateNotification()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep foreground overlay alive when user swipes Flow away from Recents
        val restartIntent = Intent(applicationContext, FloatingBubbleService::class.java).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1001,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 500L,
            pendingIntent
        )
    }

    fun updateNotification() {
        val notification = createForegroundNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        activeDictationJob?.cancel()
        liveTranscribeStreamer?.cancelAndClose()
        rollingChunkTranscriber?.cancelAll()
        TextInjectorAccessibilityService.instance?.onKeyboardVisibilityChanged = null
        removeFloatingBubble()
        onDeviceRecognizer.release()
        formattingChain.release()
        if (instance == this) {
            instance = null
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): FloatingBubbleService = this@FloatingBubbleService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun createForegroundNotification(): Notification {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val isBn = prefs.appLanguage == "bn"
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_TOGGLE_MIC
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isMasterOn = prefs.isMasterSwitchEnabled
        val actionTitle = if (!isMasterOn || isDismissedForCurrentKeyboardSession) {
            if (isBn) "মাইক দেখান" else "Show mic"
        } else {
            if (isBn) "মাইক লুকান" else "Hide mic"
        }

        val notifTitle = if (isBn) "Flow প্রস্তুত" else "Flow is ready"
        val notifText = if (!isMasterOn) {
            if (isBn) "মাইক বন্ধ আছে।" else "Mic is disabled."
        } else if (isDismissedForCurrentKeyboardSession) {
            if (isBn) "বর্তমান কিবোর্ডের জন্য মাইক সরানো হয়েছে।" else "Mic dismissed for current typing session."
        } else {
            if (isBn) "কিবোর্ড খুললেই মাইক দেখানো হবে।" else "Mic appears whenever keyboard opens."
        }

        val builder = NotificationCompat.Builder(this, FlowBanglaApp.CHANNEL_ID)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, actionTitle, togglePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)

        val raw = lastUndoTranscript
        if (!raw.isNullOrBlank()) {
            val undoIntent = Intent(this, FloatingBubbleService::class.java).apply {
                action = ACTION_UNDO_AI_EDIT
            }
            val undoPendingIntent = PendingIntent.getService(
                this, 2, undoIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val undoTitle = if (isBn) "ফিরিয়ে আনুন" else "Undo edit"
            builder.addAction(R.drawable.ic_launcher_foreground, undoTitle, undoPendingIntent)
        }

        return builder.build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = FlowBanglaApp.instance.preferencesManager

        val displayMetrics = resources.displayMetrics
        val sizePx = (prefs.bubbleSizeDp * displayMetrics.density).toInt()

        val defaultX = displayMetrics.widthPixels - sizePx - 24
        val defaultY = (displayMetrics.heightPixels * 0.55).toInt()
        val posX = if (prefs.bubblePosX != -1) prefs.bubblePosX else defaultX
        val posY = if (prefs.bubblePosY != -1) prefs.bubblePosY else defaultY

        windowParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
            alpha = prefs.bubbleOpacity.coerceIn(0.2f, 1.0f)
        }

        bubbleContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // Dedicated custom vector canvas view (Anti-Aliased, crisp, no pixelation)
        canvasView = FloatingMicCanvasView(this).apply {
            onSingleTapAction = {
                handleSingleTap()
            }
            onDoubleTapLanguageSwitch = {
                handleDoubleTapLanguageToggle()
            }
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        bubbleContainer?.addView(canvasView, lp)

        // Touch listener for dragging and click/hold
        bubbleContainer?.setOnTouchListener { _, event ->
            handleBubbleTouch(event, sizePx, displayMetrics)
        }

        try {
            windowManager?.addView(bubbleContainer, windowParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window: ${e.message}", e)
        }

        refreshLanguageBadge()
        refreshBubbleVisibility()
    }

    private fun setupLivePreviewPill() {
        val density = resources.displayMetrics.density
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(Color.parseColor("#F016181B")) // 94% #16181B surface (§7.2)
            setStroke((1f * density).toInt(), Color.parseColor("#2A2E33")) // 1dp hairline
        }

        previewTextView = TextView(this).apply {
            background = pillBg
            setTextColor(Color.parseColor("#EEEBE3")) // Ivory
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            maxLines = 2 // Two lines maximum (§7.2)
            maxWidth = (280 * density).toInt()
            visibility = View.GONE
        }

        previewWindowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = (90 * density).toInt()
        }

        try {
            windowManager?.addView(previewTextView, previewWindowParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add previewTextView window: ${e.message}")
        }
    }

    private fun showThrottledPreviewText(text: String) {
        if (text.isBlank()) return
        previewTextView?.apply {
            this.text = text
            if (visibility != View.VISIBLE) {
                alpha = 0f
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(160).start()
            }
        }
    }

    private fun hideThrottledPreviewText() {
        previewTextView?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            previewTextView?.visibility = View.GONE
        }?.start()
    }

    private fun setupDismissTargetView() {
        val density = resources.displayMetrics.density

        val targetSizePx = (64 * density).toInt()

        dismissTargetParams = WindowManager.LayoutParams(
            targetSizePx,
            targetSizePx,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }

        dismissTargetView = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#66EEEBE3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = createDismissBackground(isNear = false)
            visibility = View.GONE
        }

        try {
            windowManager?.addView(dismissTargetView, dismissTargetParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add dismiss target view: ${e.message}")
        }
    }

    private fun createDismissBackground(isNear: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isNear) {
                // Fills ivory when mic is close (§7.4)
                setColor(Color.parseColor("#EEEBE3"))
            } else {
                // Resting: 1dp ivory outline at 40% alpha, transparent fill
                setColor(Color.TRANSPARENT)
                setStroke((1f * density).toInt(), Color.parseColor("#66EEEBE3"))
            }
        }
    }

    private fun showDismissTarget() {
        dismissTargetView?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            setTextColor(Color.parseColor("#66EEEBE3"))
            background = createDismissBackground(isNear = false)
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun hideDismissTarget() {
        dismissTargetView?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            dismissTargetView?.visibility = View.GONE
        }?.start()
    }

    private fun setupPendingPasteBanner() {
        val density = resources.displayMetrics.density

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density // Fully rounded pill (§7.3)
            setColor(Color.parseColor("#1F2226")) // #1F2226 fill
            setStroke((1f * density).toInt(), Color.parseColor("#2A2E33")) // 1dp hairline
        }

        pendingPasteBannerView = TextView(this).apply {
            background = pillBg
            setTextColor(Color.parseColor("#EEEBE3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = (18 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            maxLines = 2
            maxWidth = (300 * density).toInt()
            elevation = 6f
            visibility = View.GONE
        }

        pendingPasteBannerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowParams.x
            y = (windowParams.y - (64 * density)).toInt().coerceAtLeast((24 * density).toInt())
        }

        try {
            windowManager?.addView(pendingPasteBannerView, pendingPasteBannerParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add pendingPasteBannerView window: ${e.message}")
        }
    }

    fun showPendingPasteBanner(text: String, isServiceDisconnected: Boolean = false) {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val isBengali = prefs.appLanguage == "bn" || prefs.sttLanguage == com.flowbangla.app.model.SttLanguage.BENGALI ||
            text.any { it in '\u0980'..'\u09FF' }

        // Clean typography per §7.3 & §8: 8dp ochre dot, Label style ink, Caption style inkMuted
        val line1 = if (isBengali) "কপি হয়েছে" else "Copied"
        val line2 = if (isBengali) "লেখার ঘরে ট্যাপ করুন" else "Tap a text box to paste"
        val bannerMessage = "●  $line1\n    $line2"

        val spannable = SpannableStringBuilder(bannerMessage).apply {
            // 8dp ochre dot #E3B257
            setSpan(ForegroundColorSpan(Color.parseColor("#E3B257")), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Line 1 is ivory #EEEBE3
            setSpan(ForegroundColorSpan(Color.parseColor("#EEEBE3")), 3, 3 + line1.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Line 2 is inkMuted #9EA3AB
            val line2Start = bannerMessage.indexOf(line2)
            if (line2Start != -1) {
                setSpan(ForegroundColorSpan(Color.parseColor("#9EA3AB")), line2Start, line2Start + line2.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(11, true), line2Start, line2Start + line2.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        serviceScope.launch(Dispatchers.Main) {
            val density = resources.displayMetrics.density
            pendingPasteBannerParams?.x = (windowParams.x - (36 * density).toInt()).coerceAtLeast(16)
            pendingPasteBannerParams?.y = (windowParams.y - (60 * density).toInt()).coerceAtLeast((32 * density).toInt())
            try {
                windowManager?.updateViewLayout(pendingPasteBannerView, pendingPasteBannerParams)
            } catch (_: Exception) {}

            pendingPasteBannerView?.apply {
                this.text = spannable
                setOnClickListener {
                    val a11y = TextInjectorAccessibilityService.instance
                    if (a11y != null) {
                        serviceScope.launch {
                            val outcome = a11y.injectTextSuspend(text)
                            if (outcome.status == InjectionStatus.INJECTED_PASTE || outcome.status == InjectionStatus.INJECTED_SET_TEXT) {
                                hidePendingPasteBanner()
                                vibrateSuccess()
                            }
                        }
                    } else {
                        Toast.makeText(this@FloatingBubbleService, if (isBengali) "ক্লিপবোর্ডে কপি আছে" else "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
            }

            pendingPasteBannerDismissJob?.cancel()
            pendingPasteBannerDismissJob = serviceScope.launch {
                delay(45_000L)
                hidePendingPasteBanner()
            }
        }
    }

    fun hidePendingPasteBanner() {
        serviceScope.launch(Dispatchers.Main) {
            pendingPasteBannerDismissJob?.cancel()
            pendingPasteBannerDismissJob = null
            pendingPasteBannerView?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
                pendingPasteBannerView?.visibility = View.GONE
            }?.start()
        }
    }

    private fun updateDismissTargetProximity(sizePx: Int, displayMetrics: DisplayMetrics) {
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val bubbleCenterX = windowParams.x + sizePx / 2f
        val bubbleCenterY = windowParams.y + sizePx / 2f

        val targetCenterX = screenWidth / 2f
        val targetCenterY = screenHeight - (48 * density) - (32 * density)

        val dist = Math.hypot((bubbleCenterX - targetCenterX).toDouble(), (bubbleCenterY - targetCenterY).toDouble())
        val threshold = 85 * density

        if (dist < threshold) {
            if (!isNearDismissTarget) {
                isNearDismissTarget = true
                vibrateShort()
                dismissTargetView?.setTextColor(Color.parseColor("#0D0E10")) // Dark glyph when near (§7.4)
                dismissTargetView?.background = createDismissBackground(isNear = true)
                dismissTargetView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(120)?.start()
                bubbleContainer?.alpha = 0.4f
            }
        } else {
            if (isNearDismissTarget) {
                isNearDismissTarget = false
                dismissTargetView?.setTextColor(Color.parseColor("#66EEEBE3"))
                dismissTargetView?.background = createDismissBackground(isNear = false)
                dismissTargetView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
                val prefs = FlowBanglaApp.instance.preferencesManager
                bubbleContainer?.alpha = prefs.bubbleOpacity.coerceIn(0.2f, 1.0f)
            }
        }
    }

    private fun dismissBubbleToBottom() {
        isNearDismissTarget = false
        performDismissHaptic()
        // Hide ONLY for the current keyboard session — never disable the master switch or kill the service!
        isDismissedForCurrentKeyboardSession = true
        hideDismissTarget()
        setOverlayViewVisibility(false)
        val isBn = FlowBanglaApp.instance.preferencesManager.appLanguage == "bn"
        Toast.makeText(this, if (isBn) "মাইক সরানো হয়েছে (কিবোর্ড খুললে আবার আসবে)" else "Mic dismissed for this session", Toast.LENGTH_SHORT).show()
        updateNotification()
    }

    private fun performDismissHaptic() {
        vibrateDouble()
    }

    fun forceRestoreBubbleNow() {
        isDismissedForCurrentKeyboardSession = false
        FlowBanglaApp.instance.preferencesManager.isMasterSwitchEnabled = true
        refreshBubbleVisibility()
        updateNotification()
    }

    fun showBubbleFromBottom() {
        forceRestoreBubbleNow()
    }

    fun hideBubbleDirectly() {
        isDismissedForCurrentKeyboardSession = true
        setOverlayViewVisibility(false)
        updateNotification()
    }

    fun onKeyboardVisibilityChanged(
        keyboardVisible: Boolean,
        keyboardTopY: Int = 0,
        isRestrictedAppOrField: Boolean = false
    ) {
        serviceScope.launch(Dispatchers.Main) {
            if (isRestrictedAppOrField) {
                // Always hide inside banking apps or password fields (unless actively recording or processing)
                if (currentState is TranscriptionState.Idle) {
                    setOverlayViewVisibility(false)
                }
                return@launch
            }

            val wasKeyboardVisible = isKeyboardCurrentlyVisible
            isKeyboardCurrentlyVisible = keyboardVisible

            // When the keyboard closes, reset the per-session dismiss flag!
            // That way, if the user dragged the mic to '✕' earlier, it will automatically
            // come back the next time they open the keyboard!
            if (KeyboardVisibilityDetector.shouldResetDismissOnKeyboardStateChange(wasKeyboardVisible, keyboardVisible)) {
                isDismissedForCurrentKeyboardSession = false
            }

            refreshBubbleVisibility()
        }
    }

    fun refreshBubbleVisibility() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val isRecordingOrProcessing = currentState !is TranscriptionState.Idle

        // If recording was initiated from the Inline Bar, hide the floating mic
        if (isRecordingOrProcessing && activeTriggerSurface == TriggerSurface.INLINE_BAR) {
            setOverlayViewVisibility(false)
            return
        }

        val shouldShow = prefs.enableFloatingMic && KeyboardVisibilityDetector.shouldBubbleBeVisible(
            isMasterSwitchEnabled = prefs.isMasterSwitchEnabled,
            isRecordingOrProcessing = isRecordingOrProcessing,
            isDismissedForCurrentSession = isDismissedForCurrentKeyboardSession,
            showOnlyWhenTyping = prefs.showOnlyWhenTyping,
            isKeyboardVisible = isKeyboardCurrentlyVisible
        )
        setOverlayViewVisibility(shouldShow)
    }

    fun toggleLanguage() {
        handleDoubleTapLanguageToggle()
    }

    private fun setOverlayViewVisibility(visible: Boolean) {
        val view = bubbleContainer ?: return
        val targetVis = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != targetVis) {
            view.visibility = targetVis
        }
    }

    fun applyVisibilityRule() {
        refreshBubbleVisibility()
    }

    private fun refreshLanguageBadge() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val badge = if (prefs.engineMode == EngineMode.CLOUD_AUDIO) {
            null // Cloud Audio auto-detects Bengali/English/Banglish
        } else {
            prefs.sttLanguage.badgeText
        }
        canvasView?.setLanguageBadge(badge)
    }

    private fun handleDoubleTapLanguageToggle() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        if (!prefs.isDoubleTapLanguageToggleEnabled || prefs.engineMode == EngineMode.CLOUD_AUDIO) return
        if (currentState !is TranscriptionState.Idle) return

        val nextLang = prefs.sttLanguage.toggle()
        prefs.sttLanguage = nextLang
        refreshLanguageBadge()
        vibrateDouble()
        Toast.makeText(
            this,
            "Dictation Language: ${nextLang.labelEn}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleSingleTap() {
        when (currentState) {
            is TranscriptionState.Idle -> {
                activeTriggerSurface = TriggerSurface.FLOATING_MIC
                startRecordingAction()
            }
            is TranscriptionState.Recording -> onUserConfirmDictation()
            else -> { /* Busy processing */ }
        }
    }

    fun onUserConfirmDictation() {
        if (currentState !is TranscriptionState.Recording) return
        vibrateShort()
        autoCheckpointJob?.cancel()
        autoCheckpointJob = null

        val durationSec = ((SystemClock.elapsedRealtime() - recordingStartTimeMs) / 1000L).coerceAtLeast(1L)
        val processingTimeoutMs = (12_000L + (durationSec * 650L)).coerceAtMost(90_000L)

        // Immediately switch UI to PROCESSING so user sees the Ochre spinner
        currentState = TranscriptionState.Processing
        updateUiState(currentState)

        // Start dynamic watchdog scaled to recording duration (+10s buffer)
        startProcessingWatchdog(processingTimeoutMs + 10_000L)

        val prefs = FlowBanglaApp.instance.preferencesManager
        if (prefs.engineMode == EngineMode.CLOUD_AUDIO) {
            activeDictationJob?.cancel()
            // Launch the heavy pipeline strictly on Dispatchers.IO (NEVER on Dispatchers.Main!)
            activeDictationJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    executeDictationPipelineIo()
                } catch (ce: CancellationException) {
                    Log.i(TAG, "Dictation cancelled or timed out: ${ce.message}")
                    withContext(Dispatchers.Main) {
                        currentState = TranscriptionState.Idle
                        updateUiState(currentState)
                    }
                } catch (t: Throwable) {
                    // Catch Throwable so the app process NEVER crashes
                    Log.e(TAG, "Fatal error in dictation pipeline", t)
                    withContext(Dispatchers.Main) {
                        vibrateError()
                        currentState = TranscriptionState.Error(t.message ?: "Processing error")
                        updateUiState(currentState)
                        delay(1200L)
                        currentState = TranscriptionState.Idle
                        updateUiState(currentState)
                    }
                } finally {
                    processingWatchdogJob?.cancel()
                    processingWatchdogJob = null
                }
            }
        } else {
            // Hybrid / Offline STT
            try {
                onDeviceRecognizer.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "onDeviceRecognizer.stopListening error: ${e.message}")
                currentState = TranscriptionState.Idle
                updateUiState(currentState)
                processingWatchdogJob?.cancel()
                processingWatchdogJob = null
            }
        }
    }

    private fun startProcessingWatchdog(timeoutMs: Long = 8_500L) {
        processingWatchdogJob?.cancel()
        processingWatchdogJob = serviceScope.launch(Dispatchers.Main) {
            delay(timeoutMs)
            if (currentState is TranscriptionState.Processing) {
                Log.w(TAG, "Watchdog triggered! Processing took > ${timeoutMs}ms; force-recovering to IDLE.")
                activeDictationJob?.cancel(CancellationException("Watchdog timeout"))
                currentState = TranscriptionState.Idle
                updateUiState(currentState)
            }
        }
    }

    fun onUserCancelDictation() {
        vibrateShort()
        autoCheckpointJob?.cancel()
        autoCheckpointJob = null
        processingWatchdogJob?.cancel()
        processingWatchdogJob = null
        activeDictationJob?.cancel(CancellationException("User tapped Cancel (✕)"))
        activeDictationJob = null
        activeTriggerSurface = TriggerSurface.NONE

        try {
            audioRecorder.cancelAndDeleteRecording()
        } catch (_: Exception) {}

        try {
            onDeviceRecognizer.cancel()
        } catch (_: Exception) {}

        try {
            liveTranscribeStreamer?.cancelAndClose()
        } catch (_: Exception) {}
        liveTranscribeStreamer = null

        try {
            rollingChunkTranscriber?.cancelAll()
        } catch (_: Exception) {}
        rollingChunkTranscriber = null

        hidePendingPasteBanner()
        hideThrottledPreviewText()
        currentState = TranscriptionState.Idle
        updateUiState(currentState)
    }

    private fun setupKeyboardListener() {
        val accessibilityService = TextInjectorAccessibilityService.instance
        accessibilityService?.onKeyboardVisibilityChanged = { isVisible ->
            onKeyboardVisibilityChanged(isVisible)
        }
        accessibilityService?.syncKeyboardVisibilityWithBubble()
    }

    fun updateDimensionsAndOpacity() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val displayMetrics = resources.displayMetrics
        val sizePx = (prefs.bubbleSizeDp * displayMetrics.density).toInt()

        windowParams.width = sizePx
        windowParams.height = sizePx
        windowParams.alpha = prefs.bubbleOpacity.coerceIn(0.2f, 1.0f)

        try {
            windowManager?.updateViewLayout(bubbleContainer, windowParams)
        } catch (_: Exception) {
        }
        applyVisibilityRule()
    }

    private fun handleBubbleTouch(
        event: MotionEvent,
        sizePx: Int,
        displayMetrics: DisplayMetrics
    ): Boolean {
        val prefs = FlowBanglaApp.instance.preferencesManager
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = windowParams.x
                initialY = windowParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isDragging = false

                if (prefs.interactionMode == InteractionMode.PUSH_TO_TALK) {
                    if (currentState is TranscriptionState.Idle) {
                        activeTriggerSurface = TriggerSurface.FLOATING_MIC
                        startRecordingAction()
                    }
                    return true
                }

                // If double-tap toggle is enabled in TAP_TO_TALK mode, let gestureDetector observe DOWN
                if (prefs.isDoubleTapLanguageToggleEnabled && currentState is TranscriptionState.Idle) {
                    canvasView?.gestureDetector?.onTouchEvent(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    showDismissTarget()
                }

                if (isDragging) {
                    windowParams.x = (initialX + dx).toInt()
                    windowParams.y = (initialY + dy).toInt()
                    try {
                        windowManager?.updateViewLayout(bubbleContainer, windowParams)
                    } catch (_: Exception) {
                    }
                    updateDismissTargetProximity(sizePx, displayMetrics)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - touchDownTime
                hideDismissTarget()

                if (isDragging) {
                    if (isNearDismissTarget) {
                        dismissBubbleToBottom()
                    } else {
                        snapToEdge(sizePx, displayMetrics)
                    }
                    return true
                } else if (duration < 500 && prefs.interactionMode == InteractionMode.TAP_TO_TALK) {
                    val hitZone = canvasView?.resolveHitZone(event.x, event.y) ?: com.flowbangla.app.ui.PodHitZone.MAIN_OR_CONFIRM
                    if (hitZone == com.flowbangla.app.ui.PodHitZone.CANCEL) {
                        onUserCancelDictation()
                    } else if (hitZone == com.flowbangla.app.ui.PodHitZone.MAIN_OR_CONFIRM) {
                        if (currentState is TranscriptionState.Recording) {
                            onUserConfirmDictation()
                        } else if (currentState is TranscriptionState.Processing) {
                            // in-flight processing; can cancel via Cancel (✕) button
                        } else if (prefs.isDoubleTapLanguageToggleEnabled) {
                            // Dispatch UP to gestureDetector for double-tap vs single-tap resolution
                            canvasView?.gestureDetector?.onTouchEvent(event)
                        } else {
                            // Double-tap disabled: execute immediately with zero delay
                            handleSingleTap()
                        }
                    }
                } else if (prefs.interactionMode == InteractionMode.PUSH_TO_TALK) {
                    val hitZone = canvasView?.resolveHitZone(event.x, event.y) ?: com.flowbangla.app.ui.PodHitZone.MAIN_OR_CONFIRM
                    if (hitZone == com.flowbangla.app.ui.PodHitZone.CANCEL) {
                        onUserCancelDictation()
                    } else if (currentState is TranscriptionState.Recording) {
                        onUserConfirmDictation()
                    }
                }

                if (isDragging) {
                    snapToEdge(sizePx, displayMetrics)
                }
                return true
            }
        }
        return false
    }

    private fun snapToEdge(sizePx: Int, displayMetrics: DisplayMetrics) {
        val screenWidth = displayMetrics.widthPixels
        val currentX = windowParams.x
        val targetX = if (currentX + sizePx / 2 < screenWidth / 2) {
            16
        } else {
            screenWidth - sizePx - 16
        }

        windowParams.x = targetX
        try {
            windowManager?.updateViewLayout(bubbleContainer, windowParams)
            FlowBanglaApp.instance.preferencesManager.bubblePosX = windowParams.x
            FlowBanglaApp.instance.preferencesManager.bubblePosY = windowParams.y
        } catch (_: Exception) {
        }
    }

    fun startRecordingFromInlineBar() {
        activeTriggerSurface = TriggerSurface.INLINE_BAR
        serviceScope.launch(Dispatchers.Main) {
            if (currentState is TranscriptionState.Idle) {
                startRecordingAction()
            }
        }
    }

    private fun startRecordingAction() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        if (!prefs.isConfigured) {
            val msg = when (prefs.engineMode) {
                EngineMode.CLOUD_AUDIO -> "Please enter Gemini API Key in Flow settings / জেমিনি API Key দিন"
                EngineMode.HYBRID_CLOUD_POLISH -> "Please enter ${prefs.cloudPolishProvider.displayName} API Key in Flow settings"
                EngineMode.OFFLINE_LOCAL_LLM -> "Please download Gemma model first in Flow settings / মডেল ডাউনলোড করুন"
                EngineMode.OFFLINE_LIGHTWEIGHT -> ""
            }
            if (msg.isNotEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return
            }
        }

        when (prefs.engineMode) {
            EngineMode.CLOUD_AUDIO -> {
                // In v3.5.1, live WebSocket streamer & rolling chunker are disabled during recording
                // so AudioRecorder (24kHz 64kbps AAC) has 100% exclusive access to hardware mic without contention
                val result = audioRecorder.startRecording()
                if (result.isFailure) {
                    Toast.makeText(this, "Microphone failed to start / মাইক্রোফোন চালু করা যায়নি", Toast.LENGTH_SHORT).show()
                    currentState = TranscriptionState.Idle
                    updateUiState(currentState)
                    return
                }
            }
            EngineMode.HYBRID_CLOUD_POLISH -> {
                onDeviceRecognizer.startListening(
                    language = prefs.sttLanguage,
                    preferOfflineOnly = false
                )
            }
            EngineMode.OFFLINE_LIGHTWEIGHT,
            EngineMode.OFFLINE_LOCAL_LLM -> {
                onDeviceRecognizer.startListening(
                    language = prefs.sttLanguage,
                    preferOfflineOnly = true
                )
            }
        }

        recordingStartTimeMs = SystemClock.elapsedRealtime()
        TextInjectorAccessibilityService.instance?.onRecordingStarted()
        startAutoCheckpointMonitor()

        vibrateShort()
        currentState = TranscriptionState.Recording()
        updateUiState(currentState)
    }

    private fun startAutoCheckpointMonitor() {
        autoCheckpointJob?.cancel()
        val prefs = FlowBanglaApp.instance.preferencesManager
        if (prefs.engineMode != EngineMode.CLOUD_AUDIO) return

        autoCheckpointJob = serviceScope.launch(Dispatchers.IO) {
            var segmentStartMs = SystemClock.elapsedRealtime()
            var silentConsecutiveMs = 0L

            while (isActive && currentState is TranscriptionState.Recording) {
                delay(100L)
                val elapsed = SystemClock.elapsedRealtime() - segmentStartMs
                val amp = audioRecorder.getCurrentAmplitude()

                if (amp < 1200) {
                    silentConsecutiveMs += 100L
                } else {
                    silentConsecutiveMs = 0L
                }

                val shouldCheckpoint = (elapsed >= AUTO_CHECKPOINT_SOFT_MS && silentConsecutiveMs >= 400L) ||
                    (elapsed >= AUTO_CHECKPOINT_HARD_MS)

                if (shouldCheckpoint) {
                    val completedChunkFile = audioRecorder.rotateRecordingFileSafelyIo()
                    segmentStartMs = SystemClock.elapsedRealtime()
                    silentConsecutiveMs = 0L

                    if (completedChunkFile != null && completedChunkFile.exists() && completedChunkFile.length() > 512L) {
                        serviceScope.launch(Dispatchers.IO) {
                            processAndInsertChunkIo(completedChunkFile)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processAndInsertChunkIo(chunkFile: File) {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val geminiKey = prefs.geminiApiKey.trim()
        val groqKey = prefs.groqApiKey.trim()
        val customVocab = try {
            val fc = TextInjectorAccessibilityService.instance?.extractActiveFieldContextFast() ?: ActiveFieldContext()
            FlowBanglaApp.instance.personalDictionaryManager.buildCustomVocabularyList(fc.screenProperNouns)
        } catch (_: Throwable) {
            emptyList()
        }

        val rawTranscript = try {
            if (geminiKey.isNotEmpty()) {
                geminiInteractionsAdapter.transcribeAudioFile(chunkFile, geminiKey, customVocab, useVerbatimMode = false).text
            } else if (groqKey.isNotEmpty()) {
                groqWhisperClient.transcribeAudio(chunkFile, groqKey, customVocab).text
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Chunk transcribe error: ${t.message}")
            ""
        }.trim()

        try { chunkFile.delete() } catch (_: Throwable) {}

        if (rawTranscript.isNotBlank()) {
            val formatted = formattingChain.formatSmartTranscript(
                rawTranscript = rawTranscript,
                fieldContext = ActiveFieldContext(),
                alreadySmartTranscribed = geminiKey.isNotEmpty()
            ).ifBlank { rawTranscript }

            deliverFormattedTextToTargetApp(
                rawTranscript = rawTranscript,
                formattedText = formatted,
                durationSec = 45,
                provenance = "Auto-Checkpoint (45s)"
            )
        }
    }

    private suspend fun executeDictationPipelineIo() {
        val prefs = FlowBanglaApp.instance.preferencesManager

        // STEP 1: Stop AudioRecorder strictly on Dispatchers.IO (max 1.5s timeout)
        val recordedFile = withTimeoutOrNull(1_500L) {
            audioRecorder.stopRecordingSafelyIo()
        }

        if (recordedFile == null || !recordedFile.exists() || recordedFile.length() < 256L) {
            withContext(Dispatchers.Main) {
                currentState = TranscriptionState.Idle
                updateUiState(currentState)
            }
            return
        }

        val durationSec = ((SystemClock.elapsedRealtime() - recordingStartTimeMs) / 1000L).coerceAtLeast(1L)
        val asrTimeoutMs = (12_000L + (durationSec * 650L)).coerceAtMost(90_000L)

        // STEP 2: Extract lightweight screen context with strict 150ms timeout (never block!)
        val fieldContext = withTimeoutOrNull(150L) {
            TextInjectorAccessibilityService.instance?.extractActiveFieldContextFast()
        } ?: ActiveFieldContext()

        val customVocab = try {
            FlowBanglaApp.instance.personalDictionaryManager.buildCustomVocabularyList(fieldContext.screenProperNouns)
        } catch (_: Throwable) {
            emptyList()
        }

        // STEP 3: Cloud ASR on Dispatchers.IO with duration-scaled timeout
        var rawTranscript = ""
        var recognitionSource = RecognitionSource.GEMINI_TRANSCRIBE

        val geminiKey = prefs.geminiApiKey.trim()
        val groqKey = prefs.groqApiKey.trim()

        val asrResult = withTimeoutOrNull(asrTimeoutMs) {
            try {
                if (geminiKey.isNotEmpty()) {
                    geminiInteractionsAdapter.transcribeAudioFile(
                        audioFile = recordedFile,
                        rawApiKey = geminiKey,
                        customVocabulary = customVocab,
                        useVerbatimMode = false // Native SMART mode in 1 pass!
                    ).text
                } else if (groqKey.isNotEmpty()) {
                    recognitionSource = RecognitionSource.GROQ_WHISPER
                    groqWhisperClient.transcribeAudio(recordedFile, groqKey, customVocab).text
                } else {
                    ""
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Primary ASR failed (${e.message}), trying Groq fallback if available.")
                if (groqKey.isNotEmpty()) {
                    recognitionSource = RecognitionSource.GROQ_WHISPER
                    try {
                        groqWhisperClient.transcribeAudio(recordedFile, groqKey, customVocab).text
                    } catch (_: Throwable) {
                        ""
                    }
                } else {
                    ""
                }
            }
        }.orEmpty().trim()

        rawTranscript = asrResult
        if (rawTranscript.isEmpty()) {
            // Save failed audio backup into History so user never loses their voice
            val backupFile = File(recordedFile.parentFile, "failed_${System.currentTimeMillis()}.m4a")
            try {
                recordedFile.copyTo(backupFile, overwrite = true)
                FlowBanglaApp.instance.historyManager.addRecord(
                    DictationRecord(
                        text = "রেকর্ডিং ব্যর্থ হয়েছে (ইতিহাসে ট্যাপ করে আবার দেখুন) / Dictation Failed",
                        durationSeconds = durationSec.toInt(),
                        isSuccess = false,
                        rawTranscript = "",
                        provenance = "Failed ASR",
                        audioFilePath = backupFile.absolutePath
                    )
                )
            } catch (_: Throwable) {}
            try { recordedFile.delete() } catch (_: Throwable) {}

            withContext(Dispatchers.Main) {
                currentState = TranscriptionState.Idle
                updateUiState(currentState)
            }
            return
        }

        // Clean up audio file on IO
        try {
            recordedFile.delete()
        } catch (_: Throwable) {}

        // IMMEDIATELY save raw transcript to History and Clipboard BEFORE formatting!
        val historyId = java.util.UUID.randomUUID().toString()
        try {
            FlowBanglaApp.instance.historyManager.addRecord(
                DictationRecord(
                    id = historyId,
                    text = rawTranscript,
                    durationSeconds = durationSec.toInt(),
                    isSuccess = true,
                    rawTranscript = rawTranscript,
                    provenance = if (recognitionSource == RecognitionSource.GEMINI_TRANSCRIBE) "Gemini Smart ASR (Raw)" else "Groq Whisper (Raw)"
                )
            )
            TextInjectorAccessibilityService.instance?.copyToClipboardGuaranteed(rawTranscript)
        } catch (_: Throwable) {}

        // Check for Voice Command Mode on highlighted text
        if (fieldContext.hasSelectedText && fieldContext.selectedText.isNotBlank() && geminiKey.isNotBlank()) {
            try {
                val transformResult = geminiInteractionsAdapter.runCommandTransform(
                    selectedText = fieldContext.selectedText,
                    spokenInstruction = rawTranscript,
                    rawApiKey = geminiKey
                )
                if (transformResult.text.isNotBlank()) {
                    try {
                        FlowBanglaApp.instance.historyManager.updateRecord(
                            id = historyId,
                            text = transformResult.text,
                            rawTranscript = rawTranscript,
                            provenance = "Command Mode (${transformResult.modelId})"
                        )
                    } catch (_: Throwable) {}
                    deliverFormattedTextToTargetApp(
                        rawTranscript = rawTranscript,
                        formattedText = transformResult.text,
                        durationSec = durationSec.toInt(),
                        provenance = "Command Mode (${transformResult.modelId})",
                        alreadySavedToHistory = true
                    )
                    return
                }
            } catch (ce: Throwable) {
                Log.w(TAG, "Command Mode transformation failed: ${ce.message}")
            }
        }

        // STEP 4: Fast Formatting & Post-Processing on Dispatchers.IO (max 3s timeout)
        val formattedText = withTimeoutOrNull(3_000L) {
            formattingChain.formatSmartTranscript(
                rawTranscript = rawTranscript,
                fieldContext = fieldContext,
                alreadySmartTranscribed = (recognitionSource == RecognitionSource.GEMINI_TRANSCRIBE)
            )
        } ?: formattingChain.formatLocalInstantOnly(rawTranscript, fieldContext)

        val finalCleanText = formattedText.ifBlank { rawTranscript }

        try {
            FlowBanglaApp.instance.historyManager.updateRecord(
                id = historyId,
                text = finalCleanText,
                rawTranscript = rawTranscript,
                provenance = if (recognitionSource == RecognitionSource.GEMINI_TRANSCRIBE) "Gemini Smart ASR" else "Groq Whisper"
            )
        } catch (_: Throwable) {}

        deliverFormattedTextToTargetApp(
            rawTranscript = rawTranscript,
            formattedText = finalCleanText,
            durationSec = durationSec.toInt(),
            provenance = if (recognitionSource == RecognitionSource.GEMINI_TRANSCRIBE) "Gemini Smart ASR" else "Groq Whisper",
            alreadySavedToHistory = true
        )
    }

    private fun processOnDeviceTranscript(rawTranscript: String) {
        activeDictationJob?.cancel()
        activeDictationJob = serviceScope.launch {
            currentState = TranscriptionState.Processing
            updateUiState(currentState)

            if (rawTranscript.isBlank()) {
                processingWatchdogJob?.cancel()
                processingWatchdogJob = null
                handleError("No speech detected / কোনো কথা শোনা যায়নি")
                return@launch
            }

            val a11y = TextInjectorAccessibilityService.instance
            val fieldContext = a11y?.extractActiveFieldContextFast() ?: ActiveFieldContext()
            val prefs = FlowBanglaApp.instance.preferencesManager

            // Check for Voice Command Mode on highlighted text
            if (fieldContext.hasSelectedText && fieldContext.selectedText.isNotBlank() && prefs.geminiApiKey.isNotBlank()) {
                try {
                    val transformResult = geminiInteractionsAdapter.runCommandTransform(
                        selectedText = fieldContext.selectedText,
                        spokenInstruction = rawTranscript,
                        rawApiKey = prefs.geminiApiKey
                    )
                    if (transformResult.text.isNotBlank()) {
                        processingWatchdogJob?.cancel()
                        processingWatchdogJob = null
                        deliverFormattedTextToTargetApp(
                            rawTranscript = rawTranscript,
                            formattedText = transformResult.text,
                            durationSec = 0,
                            provenance = "Command Mode (${transformResult.modelId})"
                        )
                        return@launch
                    }
                } catch (ce: Exception) {
                    Log.w(TAG, "Command Mode transformation failed: ${ce.message}")
                }
            }

            // Step 2 & 3: Format the on-device transcript through FormattingChain with Screen/Cursor Context
            val formatResult = formattingChain.format(rawTranscript, RecognitionSource.ANDROID_STT, fieldContext, isSmartMode = false)
            processingWatchdogJob?.cancel()
            processingWatchdogJob = null
            deliverFormattedTextToTargetApp(
                rawTranscript = rawTranscript,
                formattedText = formatResult.formattedText,
                durationSec = 0,
                provenance = formatResult.displaySummary
            )
        }
    }

    private suspend fun deliverFormattedTextToTargetApp(
        rawTranscript: String,
        formattedText: String,
        durationSec: Int = 0,
        provenance: String = "",
        alreadySavedToHistory: Boolean = false
    ) {
        // Always save to History first so zero words are ever lost for user's father!
        if (!alreadySavedToHistory) {
            try {
                FlowBanglaApp.instance.historyManager.addRecord(
                    DictationRecord(
                        text = formattedText,
                        durationSeconds = durationSec,
                        isSuccess = true,
                        rawTranscript = rawTranscript,
                        provenance = provenance
                    )
                )
            } catch (_: Throwable) {}
        }

        // Track last dictation pair for 1-tap Undo AI Edit
        if (rawTranscript.isNotBlank() && rawTranscript.trim() != formattedText.trim()) {
            lastUndoTranscript = rawTranscript
        } else {
            lastUndoTranscript = null
        }

        // Deliver to Target App with strict 600ms timeout so Accessibility IPC NEVER freezes UI!
        withContext(Dispatchers.Main) {
            updateNotification()
            val injected = withTimeoutOrNull(600L) {
                val a11y = TextInjectorAccessibilityService.instance
                if (a11y != null) {
                    a11y.injectTextFastAndSafe(formattedText)
                } else {
                    copyToClipboardFallback(formattedText)
                    false
                }
            } ?: false

            if (injected) {
                hidePendingPasteBanner()
                vibrateSuccess()
                currentState = TranscriptionState.Success(formattedText)
                updateUiState(currentState)
                delay(260L)
                currentState = TranscriptionState.Idle
                updateUiState(currentState)
                applyVisibilityRule()
            } else {
                // Text is safely on clipboard; show non-blocking recovery pill
                copyToClipboardFallback(formattedText)
                showPendingPasteBanner(formattedText)
                currentState = TranscriptionState.Idle
                updateUiState(currentState)
                applyVisibilityRule()
            }
        }
    }

    private fun copyToClipboardFallback(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("FlowDictation", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Throwable) {
            Log.e(TAG, "Direct clipboard copy fallback failed: ${e.message}")
        }
    }

    private fun handleSuccess(
        text: String,
        durationSec: Int,
        rawTranscript: String = "",
        provenance: String = ""
    ) {
        serviceScope.launch {
            deliverFormattedTextToTargetApp(
                rawTranscript = rawTranscript,
                formattedText = text,
                durationSec = durationSec,
                provenance = provenance
            )
        }
    }

    private fun handleError(message: String) {
        vibrateError()
        currentState = TranscriptionState.Error(message)
        updateUiState(currentState)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        serviceScope.launch {
            delay(2000)
            currentState = TranscriptionState.Idle
            updateUiState(currentState)
            applyVisibilityRule()
        }
    }

    private fun updateUiState(state: TranscriptionState) {
        if (state is TranscriptionState.Idle) {
            activeTriggerSurface = TriggerSurface.NONE
        }

        val isInlineActive = activeTriggerSurface == TriggerSurface.INLINE_BAR
        val isFloatingActive = activeTriggerSurface == TriggerSurface.FLOATING_MIC

        if (isInlineActive) {
            // Started from Inline Bar: expand inline bar, hide floating mic during recording/processing
            setOverlayViewVisibility(false)
            val stripState = when (state) {
                is TranscriptionState.Idle -> KeyboardStripOverlayController.StripState.IDLE
                is TranscriptionState.Recording -> KeyboardStripOverlayController.StripState.RECORDING
                is TranscriptionState.Processing -> KeyboardStripOverlayController.StripState.PROCESSING
                is TranscriptionState.Success -> KeyboardStripOverlayController.StripState.INSERTED
                is TranscriptionState.Error -> KeyboardStripOverlayController.StripState.IDLE
            }
            TextInjectorAccessibilityService.instance?.updateKeyboardChipState(stripState)
        } else if (isFloatingActive) {
            // Started from Floating Mic: expand floating pod, keep inline bar IDLE
            updateBubbleDimensionsForState(state)
            canvasView?.setState(state)
            TextInjectorAccessibilityService.instance?.updateKeyboardChipState(KeyboardStripOverlayController.StripState.IDLE)
        } else {
            updateBubbleDimensionsForState(state)
            canvasView?.setState(state)
            val stripState = when (state) {
                is TranscriptionState.Idle -> KeyboardStripOverlayController.StripState.IDLE
                is TranscriptionState.Recording -> KeyboardStripOverlayController.StripState.RECORDING
                is TranscriptionState.Processing -> KeyboardStripOverlayController.StripState.PROCESSING
                is TranscriptionState.Success -> KeyboardStripOverlayController.StripState.INSERTED
                is TranscriptionState.Error -> KeyboardStripOverlayController.StripState.IDLE
            }
            TextInjectorAccessibilityService.instance?.updateKeyboardChipState(stripState)
        }
        applyVisibilityRule()
    }

    private fun updateBubbleDimensionsForState(state: TranscriptionState) {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val idleSizePx = (prefs.bubbleSizeDp * density).toInt()

        val isNowActivePod = state is TranscriptionState.Recording || state is TranscriptionState.Processing
        val wasActivePod = windowParams.width > idleSizePx

        // CRITICAL FIX: If transitioning between RECORDING and PROCESSING,
        // do NOT call windowManager.updateViewLayout()! Both states share the exact same
        // 176dp active pod width so the transition is a 100% in-memory canvas redraw with ZERO IPC overhead!
        if (isNowActivePod && wasActivePod) {
            return
        }

        val targetWidthDp = if (isNowActivePod) 176 else prefs.bubbleSizeDp
        val targetWidthPx = (targetWidthDp * density).toInt()
        val targetHeightPx = idleSizePx

        val isDockedOnRight = (windowParams.x + windowParams.width / 2) >= (screenWidth / 2)
        canvasView?.isDockedOnRight = isDockedOnRight

        if (isDockedOnRight) {
            windowParams.x = (screenWidth - targetWidthPx - (16 * density).toInt()).coerceAtLeast(0)
        } else {
            windowParams.x = (16 * density).toInt()
        }

        windowParams.width = targetWidthPx
        windowParams.height = targetHeightPx

        try {
            windowManager?.updateViewLayout(bubbleContainer, windowParams)
        } catch (_: Exception) {}
    }

    private fun vibrateShort() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibrate(25) // Light tick per §3.6
    }

    private fun vibrateDouble() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibrate(25) // Light tick per §3.6
    }

    private fun vibrateSuccess() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibrate(35) // Single soft tick per §3.6
    }

    private fun vibrateError() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibratePattern(longArrayOf(0, 30, 40, 30)) // Double soft tick, 40ms apart per §3.6
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun removeFloatingBubble() {
        try {
            if (previewTextView != null) {
                windowManager?.removeView(previewTextView)
                previewTextView = null
            }
            if (pendingPasteBannerView != null) {
                windowManager?.removeView(pendingPasteBannerView)
                pendingPasteBannerView = null
            }
            if (dismissTargetView != null) {
                windowManager?.removeView(dismissTargetView)
                dismissTargetView = null
            }
            if (bubbleContainer != null) {
                windowManager?.removeView(bubbleContainer)
                bubbleContainer = null
                canvasView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view: ${e.message}")
        }
    }

    fun startRecordingFromExternal() {
        serviceScope.launch(Dispatchers.Main) {
            if (currentState is TranscriptionState.Idle) {
                startRecordingAction()
            }
        }
    }

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.flowbangla.action.START"
        const val ACTION_STOP_SERVICE = "com.flowbangla.action.STOP"
        const val ACTION_REFRESH_SETTINGS = "com.flowbangla.action.REFRESH_SETTINGS"
        const val ACTION_TOGGLE_MIC = "com.flowbangla.action.TOGGLE_MIC"
        const val ACTION_SHOW_MIC = "com.flowbangla.action.SHOW_MIC"
        const val ACTION_RETRY_PASTE = "com.flowbangla.action.RETRY_PASTE"
        const val ACTION_UNDO_AI_EDIT = "com.flowbangla.action.UNDO_AI_EDIT"
        const val ACTION_START_INLINE_DICTATION = "com.flowbangla.app.action.START_INLINE_DICTATION"

        var instance: FloatingBubbleService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun startIfPermitted(context: Context) {
            val prefs = FlowBanglaApp.instance.preferencesManager
            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
            if (hasOverlay && prefs.isConfigured && !isRunning()) {
                val intent = Intent(context, FloatingBubbleService::class.java).apply {
                    action = ACTION_START_SERVICE
                }
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start FloatingBubbleService", e)
                }
            }
        }
    }
}
