package com.flowbangla.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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

    // Transcription & Formatting engines
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var geminiInteractionsAdapter: GeminiInteractionsAdapter
    private lateinit var groqWhisperClient: GroqWhisperClient
    private lateinit var onDeviceRecognizer: OnDeviceSpeechRecognizer
    private lateinit var formattingChain: FormattingChain

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentState: TranscriptionState = TranscriptionState.Idle
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
        setupLivePreviewPill()
        setupKeyboardListener()
    }

    private fun initializeEngines() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val okHttpClient = OkHttpClient()
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
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopSelf()
            ACTION_REFRESH_SETTINGS -> {
                updateDimensionsAndOpacity()
                refreshLanguageBadge()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        TextInjectorAccessibilityService.instance?.onKeyboardVisibilityChanged = null
        removeFloatingBubble()
        onDeviceRecognizer.release()
        formattingChain.release()
        if (instance == this) {
            instance = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FlowBanglaApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = FlowBanglaApp.instance.preferencesManager

        val displayMetrics = resources.displayMetrics
        val sizePx = (prefs.bubbleSizeDp * displayMetrics.density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val defaultX = displayMetrics.widthPixels - sizePx - 24
        val defaultY = (displayMetrics.heightPixels * 0.55).toInt()
        val posX = if (prefs.bubblePosX != -1) prefs.bubblePosX else defaultX
        val posY = if (prefs.bubblePosY != -1) prefs.bubblePosY else defaultY

        windowParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
    }

    private fun setupLivePreviewPill() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(Color.parseColor("#EE0F172A"))
            setStroke((1.5f * density).toInt(), Color.parseColor("#475569"))
        }

        previewTextView = TextView(this).apply {
            background = pillBg
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = (14 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            maxLines = 3
            maxWidth = (260 * density).toInt()
            visibility = View.GONE
        }

        previewWindowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
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
            if (visibility != View.VISIBLE) visibility = View.VISIBLE
        }
    }

    private fun hideThrottledPreviewText() {
        previewTextView?.visibility = View.GONE
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
            is TranscriptionState.Idle -> startRecordingAction()
            is TranscriptionState.Recording -> stopRecordingAndProcess()
            else -> { /* Busy processing */ }
        }
    }

    private fun setupKeyboardListener() {
        val accessibilityService = TextInjectorAccessibilityService.instance
        accessibilityService?.onKeyboardVisibilityChanged = { isVisible ->
            isKeyboardActive = isVisible
            applyVisibilityRule()
        }
        accessibilityService?.checkInputFocusState()
    }

    fun applyVisibilityRule() {
        val prefs = FlowBanglaApp.instance.preferencesManager
        val shouldShow = if (prefs.showOnlyWithKeyboard) {
            isKeyboardActive || currentState !is TranscriptionState.Idle
        } else {
            true
        }

        bubbleContainer?.visibility = if (shouldShow) View.VISIBLE else View.GONE
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
                }

                if (isDragging) {
                    windowParams.x = (initialX + dx).toInt()
                    windowParams.y = (initialY + dy).toInt()
                    try {
                        windowManager?.updateViewLayout(bubbleContainer, windowParams)
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - touchDownTime

                if (!isDragging && duration < 500 && prefs.interactionMode == InteractionMode.TAP_TO_TALK) {
                    if (currentState is TranscriptionState.Recording) {
                        // Instant stop on tap while recording (no double-tap delay needed during speech)
                        stopRecordingAndProcess()
                    } else if (prefs.isDoubleTapLanguageToggleEnabled) {
                        // Dispatch UP to gestureDetector for double-tap vs single-tap resolution
                        canvasView?.gestureDetector?.onTouchEvent(event)
                    } else {
                        // Double-tap disabled: execute immediately with zero delay
                        handleSingleTap()
                    }
                } else if (prefs.interactionMode == InteractionMode.PUSH_TO_TALK) {
                    if (currentState is TranscriptionState.Recording) {
                        stopRecordingAndProcess()
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

        vibrateShort()
        currentState = TranscriptionState.Recording()
        updateUiState(currentState)

        when (prefs.engineMode) {
            EngineMode.CLOUD_AUDIO -> {
                val result = audioRecorder.startRecording()
                if (result.isFailure) {
                    Toast.makeText(this, "Microphone failed to start / মাইক্রোফোন চালু করা যায়নি", Toast.LENGTH_SHORT).show()
                    currentState = TranscriptionState.Idle
                    updateUiState(currentState)
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
    }

    private fun stopRecordingAndProcess() {
        vibrateDouble()
        currentState = TranscriptionState.Processing
        updateUiState(currentState)

        val prefs = FlowBanglaApp.instance.preferencesManager
        when (prefs.engineMode) {
            EngineMode.CLOUD_AUDIO -> {
                val result = audioRecorder.stopRecording()
                val audioFile = result.getOrNull()?.file
                val durationMs = result.getOrNull()?.durationMs ?: 0

                if (audioFile == null) {
                    handleError("Recording file not found / অডিও ফাইল পাওয়া যায়নি")
                    return
                }

                serviceScope.launch {
                    val customVocab = prefs.getPersonalVocabularyList()
                    val a11y = TextInjectorAccessibilityService.instance
                    val fieldContext = a11y?.extractActiveFieldContext() ?: ActiveFieldContext()

                    var recognitionSource = RecognitionSource.GEMINI_TRANSCRIBE
                    var rawTranscript = ""
                    var lastErrorMsg: String? = null

                    // 1. Primary Cloud ASR: Gemini Interactions API (gemini-3.5-transcribe)
                    try {
                        val interactionRes = geminiInteractionsAdapter.transcribeAudioFile(
                            audioFile = audioFile,
                            rawApiKey = prefs.geminiApiKey,
                            languageCodes = emptyList(), // Automatic Bangla + English code-switching
                            customVocabulary = customVocab,
                            useVerbatimMode = true
                        )
                        rawTranscript = interactionRes.text
                        recognitionSource = RecognitionSource.GEMINI_TRANSCRIBE
                    } catch (e: Exception) {
                        Log.w(TAG, "Gemini transcription failed (${e.message}), checking Groq Whisper...")
                        lastErrorMsg = e.message
                        // 2. Secondary Cloud ASR: Groq Whisper Large V3 fallback
                        if (prefs.groqApiKey.isNotBlank()) {
                            try {
                                val groqRes = groqWhisperClient.transcribeAudio(
                                    audioFile = audioFile,
                                    apiKey = prefs.groqApiKey,
                                    customVocabulary = customVocab
                                )
                                rawTranscript = groqRes.text
                                recognitionSource = RecognitionSource.GROQ_WHISPER
                            } catch (ge: Exception) {
                                lastErrorMsg = ge.message
                            }
                        }
                    } finally {
                        audioFile.delete()
                    }

                    if (rawTranscript.isNotBlank()) {
                        // Step 2 & 3: Decoupled formatting through FormattingChain with Screen/Cursor Context
                        val formatResult = formattingChain.format(rawTranscript, recognitionSource, fieldContext)
                        handleSuccess(
                            text = formatResult.formattedText,
                            durationSec = (durationMs / 1000).toInt(),
                            rawTranscript = rawTranscript,
                            provenance = formatResult.displaySummary
                        )
                    } else {
                        handleError(lastErrorMsg ?: "কোন কথা শনাক্ত করা যায়নি (No speech detected).")
                    }
                }
            }
            EngineMode.HYBRID_CLOUD_POLISH,
            EngineMode.OFFLINE_LIGHTWEIGHT,
            EngineMode.OFFLINE_LOCAL_LLM -> {
                onDeviceRecognizer.stopListening()
            }
        }
    }

    private fun processOnDeviceTranscript(rawTranscript: String) {
        serviceScope.launch {
            currentState = TranscriptionState.Processing
            updateUiState(currentState)

            if (rawTranscript.isBlank()) {
                handleError("No speech detected / কোনো কথা শোনা যায়নি")
                return@launch
            }

            val a11y = TextInjectorAccessibilityService.instance
            val fieldContext = a11y?.extractActiveFieldContext() ?: ActiveFieldContext()

            // Step 2 & 3: Format the on-device transcript through FormattingChain with Screen/Cursor Context
            val formatResult = formattingChain.format(rawTranscript, RecognitionSource.ANDROID_STT, fieldContext)
            handleSuccess(
                text = formatResult.formattedText,
                durationSec = 0,
                rawTranscript = rawTranscript,
                provenance = formatResult.displaySummary
            )
        }
    }

    private fun handleSuccess(
        text: String,
        durationSec: Int,
        rawTranscript: String = "",
        provenance: String = ""
    ) {
        vibrateSuccess()
        currentState = TranscriptionState.Success(text)
        updateUiState(currentState)

        val injected = TextInjectorAccessibilityService.instance?.injectText(text) ?: false

        FlowBanglaApp.instance.historyManager.addRecord(
            DictationRecord(
                text = text,
                durationSeconds = durationSec,
                isSuccess = injected,
                rawTranscript = rawTranscript,
                provenance = provenance
            )
        )

        serviceScope.launch {
            delay(1200)
            currentState = TranscriptionState.Idle
            updateUiState(currentState)
            applyVisibilityRule()
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
        canvasView?.setState(state)
        applyVisibilityRule()
    }

    private fun vibrateShort() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibrate(40)
    }

    private fun vibrateDouble() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibratePattern(longArrayOf(0, 30, 60, 30))
    }

    private fun vibrateSuccess() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibrate(60)
    }

    private fun vibrateError() {
        if (!FlowBanglaApp.instance.preferencesManager.hapticFeedbackEnabled) return
        vibratePattern(longArrayOf(0, 80, 80, 80))
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
            if (bubbleContainer != null) {
                windowManager?.removeView(bubbleContainer)
                bubbleContainer = null
                canvasView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.flowbangla.action.START"
        const val ACTION_STOP_SERVICE = "com.flowbangla.action.STOP"
        const val ACTION_REFRESH_SETTINGS = "com.flowbangla.action.REFRESH_SETTINGS"

        var instance: FloatingBubbleService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
