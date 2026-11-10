package com.flowbangla.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowbangla.app.FlowBanglaApp
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.diagnostics.ConnectivityLabReport
import com.flowbangla.app.diagnostics.ConnectivityLabRunner
import com.flowbangla.app.format.AutoCleanupLevel
import com.flowbangla.app.model.CloudPolishProvider
import com.flowbangla.app.model.DictationRecord
import com.flowbangla.app.model.EngineMode
import com.flowbangla.app.model.InteractionMode
import com.flowbangla.app.model.SttLanguage
import com.flowbangla.app.offline.ModelDownloadManager
import com.flowbangla.app.offline.ModelDownloadState
import com.flowbangla.app.service.FloatingBubbleService
import com.flowbangla.app.service.TextInjectorAccessibilityService
import com.flowbangla.app.ui.components.*
import com.flowbangla.app.ui.mic.drawDandaMic
import com.flowbangla.app.ui.theme.*
import com.flowbangla.app.util.FlowPermissionState
import com.flowbangla.app.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Microphone permission is required for voice dictation", Toast.LENGTH_LONG).show()
            }
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkEssentialPermissions()

        setContent {
            val prefs = remember { FlowBanglaApp.instance.preferencesManager }
            var appearanceTheme by remember { mutableStateOf(prefs.appearanceTheme) }
            var accentColorKey by remember { mutableStateOf(prefs.accentColorKey) }
            val darkTheme = when (appearanceTheme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            var appLanguage by remember { mutableStateOf(prefs.appLanguage) }

            FlowBanglaTheme(
                darkTheme = darkTheme,
                isBangla = appLanguage == "bn",
                accentColor = getAccentColor(accentColorKey)
            ) {
                MainScreen(
                    currentAppearance = appearanceTheme,
                    onAppearanceChange = { newTheme ->
                        appearanceTheme = newTheme
                        prefs.appearanceTheme = newTheme
                    },
                    currentAccentKey = accentColorKey,
                    onAccentChange = { newAccent ->
                        accentColorKey = newAccent
                        prefs.accentColorKey = newAccent
                    },
                    currentLanguage = appLanguage,
                    onLanguageChange = { newLang ->
                        appLanguage = newLang
                        prefs.appLanguage = newLang
                    },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestAccessibilityPermission = { requestAccessibilityPermission() },
                    onRequestXiaomiAutostart = { requestXiaomiAutostart() },
                    onRequestBatteryExemption = { requestBatteryOptimizationExemption() },
                    onRequestSamsungDeviceCare = { requestSamsungDeviceCare() },
                    onRequestXiaomiLockApps = { requestXiaomiLockApps() }
                )
            }
        }
    }

    private fun checkEssentialPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestXiaomiAutostart() {
        com.flowbangla.app.util.OemAutostartHelper.openOemAutostartSettings(this)
    }

    private fun requestBatteryOptimizationExemption() {
        com.flowbangla.app.util.OemAutostartHelper.requestIgnoreBatteryOptimizations(this)
    }

    private fun requestSamsungDeviceCare() {
        com.flowbangla.app.util.OemAutostartHelper.openOemAutostartSettings(this)
    }

    private fun requestXiaomiLockApps() {
        com.flowbangla.app.util.OemAutostartHelper.openXiaomiLockAppsInRecents(this)
    }

    override fun onStop() {
        super.onStop()
        // Whenever all critical permissions are granted and the user leaves the Flow app,
        // hide Flow's task card from the Recent Apps list so Xiaomi's "Clear All" gesture NEVER kills it!
        try {
            if (PermissionHelper.checkAllPermissions(this).allCriticalGranted) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.appTasks?.forEach { task ->
                    task.setExcludeFromRecents(true)
                }
            }
        } catch (_: Throwable) {}
    }
}

// Clean Copy Deck Localization Dictionary (§8)
object L10n {
    fun t(key: String, isBn: Boolean): String {
        return if (isBn) bnMap[key] ?: enMap[key] ?: key else enMap[key] ?: key
    }

    private val enMap = mapOf(
        "app_title" to "Flow",
        "nav_home" to "Home",
        "nav_history" to "History",
        "status_ready" to "Ready.",
        "status_paused" to "Paused.",
        "status_setup" to "Needs setup.",
        "hero_caption_ready" to "The mic shows above your keyboard.",
        "hero_caption_paused" to "Turn on the switch to start the floating mic.",
        "hero_caption_setup" to "Turn on access to start.",
        "show_mic" to "Show mic",
        "hide_mic" to "Hide mic",
        "setup" to "Setup",
        "dictation" to "Dictation",
        "engine_cloud" to "Cloud audio",
        "engine_cloud_desc" to "Best for mixed Bangla and English. Needs internet and a key.",
        "engine_fast" to "Fast cloud",
        "engine_fast_desc" to "Quick results. Transcribes on the phone, then polishes in the cloud.",
        "engine_offline" to "Offline",
        "engine_offline_desc" to "No internet. Basic punctuation and filler removal.",
        "engine_offline_ai" to "Offline AI",
        "engine_offline_ai_desc" to "No internet. Larger download. Uses more memory while active.",
        "recommended" to "Recommended",
        "keys" to "Keys",
        "test_connection" to "Test connection",
        "testing" to "Testing...",
        "voice_and_polish" to "Voice and polish",
        "smart_polish" to "Smart polish",
        "smart_polish_desc" to "Removes fillers like um and uh, and adds punctuation.",
        "cleanup" to "Clean-up",
        "cleanup_off_desc" to "Your words exactly as spoken.",
        "cleanup_light_desc" to "Removes filler words and formats lists.",
        "cleanup_full_desc" to "Also fixes changes of mind and smooths the wording.",
        "drop_period" to "Drop the final full stop in chats",
        "drop_period_desc" to "Short messages in WhatsApp and Messenger finish without a full stop.",
        "microphone" to "Microphone",
        "how_to_start" to "How to start",
        "tap_to_start" to "Tap to start",
        "hold_to_talk" to "Hold to talk",
        "size" to "Size",
        "opacity" to "Opacity",
        "haptic" to "Haptic feedback",
        "haptic_desc" to "A short tick when recording starts and stops.",
        "show_only_typing" to "Show only when typing",
        "show_only_typing_desc" to "The mic appears when a text box is active and hides when you stop typing.",
        "trigger_style" to "Trigger style",
        "trigger_both" to "Inline Bar + Floating Mic",
        "trigger_keyboard_only" to "Inline Keyboard Bar",
        "trigger_floating_only" to "Floating Mic Only",
        "vocabulary" to "Vocabulary",
        "personal_dict" to "Personal dictionary",
        "personal_dict_desc" to "Words you correct are learned for 15 seconds after you paste.",
        "snippets" to "Voice snippets",
        "snippets_desc" to "Short phrases that expand into longer text.",
        "about" to "About",
        "appearance" to "Appearance",
        "guide" to "Guide",
        "diagnostics" to "Diagnostics",
        "version" to "Version 3.9.0 (19)",
        "added" to "Added",
        "not_added" to "Not added",
        "save_key_first" to "Please save required API Key first"
    )

    private val bnMap = mapOf(
        "app_title" to "Flow",
        "nav_home" to "হোম",
        "nav_history" to "হিস্ট্রি",
        "status_ready" to "প্রস্তুত।",
        "status_paused" to "বন্ধ আছে।",
        "status_setup" to "সেটআপ বাকি।",
        "hero_caption_ready" to "মাইক কিবোর্ডের উপরে দেখানো হচ্ছে।",
        "hero_caption_paused" to "চালু করতে ডানের সুইচটি অন করুন।",
        "hero_caption_setup" to "শুরু করতে অ্যাক্সেস চালু করুন।",
        "show_mic" to "মাইক দেখান",
        "hide_mic" to "মাইক লুকান",
        "setup" to "সেটআপ",
        "dictation" to "ডিক্টেশন মোড",
        "engine_cloud" to "ক্লাউড অডিও",
        "engine_cloud_desc" to "বাংলা ও ইংরেজি মেশানো কথার জন্য সবচেয়ে ভালো। ইন্টারনেট ও একটি কী লাগবে।",
        "engine_fast" to "দ্রুতগতির ক্লাউড",
        "engine_fast_desc" to "ফোনে কথা থেকে লেখা তৈরি করে ক্লাউডে সাজিয়ে নেয়।",
        "engine_offline" to "অফলাইন",
        "engine_offline_desc" to "ইন্টারনেট ছাড়া। মৌলিক বিরামচিহ্ন ও ফিলার বাদ।",
        "engine_offline_ai" to "অফলাইন এআই",
        "engine_offline_ai_desc" to "ইন্টারনেট ছাড়া। বড় মডেল ডাউনলোড প্রয়োজন।",
        "recommended" to "সুপারিশকৃত",
        "keys" to "এপিআই কী",
        "test_connection" to "সংযোগ যাচাই করুন",
        "testing" to "যাচাই করা হচ্ছে...",
        "voice_and_polish" to "ভয়েস ও পলিশ",
        "smart_polish" to "স্মার্ট পলিশ",
        "smart_polish_desc" to "উম, আহ জাতীয় শব্দ বাদ দিয়ে দাঁড়ি-কমা বসায়।",
        "cleanup" to "ক্লিন-আপ লেভেল",
        "cleanup_off_desc" to "ঠিক যেমন বলা হয়েছে তেমন লেখা।",
        "cleanup_light_desc" to "অপ্রয়োজনীয় ফিলার শব্দ বাদ দিয়ে তালিকা সাজায়।",
        "cleanup_full_desc" to "মত বদলের কথা ঠিক করে ও বাক্য মসৃণ করে।",
        "drop_period" to "মেসেজিংয়ে শেষ দাঁড়ি বাদ",
        "drop_period_desc" to "হোয়াটসঅ্যাপ ও মেসেঞ্জারের ছোট বার্তায় শেষের দাঁড়ি থাকে না।",
        "microphone" to "মাইক্রোফোন",
        "how_to_start" to "কথা শুরু করার ধরন",
        "tap_to_start" to "১ বার ট্যাপ",
        "hold_to_talk" to "চেপে ধরে কথা",
        "size" to "আকার",
        "opacity" to "স্বচ্ছতা",
        "haptic" to "হ্যাপটিক ভাইব্রেশন",
        "haptic_desc" to "রেকর্ডিং শুরু ও শেষ হলে মৃদু ভাইব্রেশন দেবে।",
        "show_only_typing" to "শুধু টাইপ করার সময় দেখান",
        "show_only_typing_desc" to "কিবোর্ড চালু হলে মাইক ভেসে উঠবে এবং অন্য সময় লুকিয়ে থাকবে।",
        "trigger_style" to "ট্রিগার স্টাইল",
        "trigger_both" to "ইনলাইন বার + ফ্লোটিং মাইক",
        "trigger_keyboard_only" to "কিবোর্ড ইনলাইন বার",
        "trigger_floating_only" to "শুধু ফ্লোটিং মাইক",
        "vocabulary" to "শব্দভাণ্ডার",
        "personal_dict" to "ব্যক্তিগত ডিকশনারি",
        "personal_dict_desc" to "ভুল সংশোধন করলে ১৫ সেকেন্ডের মধ্যে তা শিখে নেয়।",
        "snippets" to "ভয়েস স্নsnippet",
        "snippets_desc" to "ছোট কথায় বড় টেক্সট বসানোর শর্টকাট।",
        "about" to "অ্যাপ পরিচিতি",
        "appearance" to "থিম",
        "guide" to "সেটাপ নির্দেশিকা",
        "diagnostics" to "ডায়াগনস্টিক",
        "version" to "ভার্সন ৩.৯.০ (১৯)",
        "added" to "যুক্ত আছে",
        "not_added" to "যুক্ত নেই",
        "save_key_first" to "আগে প্রয়োজনীয় API Key সেভ করুন"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentAppearance: String,
    onAppearanceChange: (String) -> Unit,
    currentAccentKey: String,
    onAccentChange: (String) -> Unit,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestXiaomiAutostart: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestSamsungDeviceCare: () -> Unit,
    onRequestXiaomiLockApps: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { FlowBanglaApp.instance.preferencesManager }
    val historyManager = remember { FlowBanglaApp.instance.historyManager }
    val dictionaryManager = remember { FlowBanglaApp.instance.personalDictionaryManager }
    val snippetManager = remember { FlowBanglaApp.instance.snippetManager }
    val modelDownloadManager = remember { ModelDownloadManager(context, prefs) }
    val scope = rememberCoroutineScope()

    var triggerStyle by remember { mutableStateOf(prefs.triggerStyle) }

    val isBn = currentLanguage == "bn"
    fun str(key: String): String = L10n.t(key, isBn)

    var isServiceRunning by remember { mutableStateOf(FloatingBubbleService.isRunning()) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Home, 1 = History
    var selectedAccentKey by remember(currentAccentKey) { mutableStateOf(currentAccentKey) }

    // Sheets & Dialogs
    var showPermissionsSheet by remember { mutableStateOf(false) }
    var activeKeySheetProvider by remember { mutableStateOf<String?>(null) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showDictionarySheet by remember { mutableStateOf(false) }
    var showSnippetsSheet by remember { mutableStateOf(false) }

    // State Variables
    var selectedEngineMode by remember { mutableStateOf(prefs.engineMode) }
    var geminiKeyText by remember { mutableStateOf(prefs.geminiApiKey) }
    var groqKeyText by remember { mutableStateOf(prefs.groqApiKey) }
    var cerebrasKeyText by remember { mutableStateOf(prefs.cerebrasApiKey) }
    var openRouterKeyText by remember { mutableStateOf(prefs.openRouterApiKey) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectivityReport by remember { mutableStateOf<ConnectivityLabReport?>(null) }

    var isSmartPolish by remember { mutableStateOf(prefs.smartPolishEnabled) }
    var autoCleanupLevel by remember { mutableStateOf(prefs.autoCleanupLevel) }
    var casualMessagingDropPeriod by remember { mutableStateOf(prefs.casualMessagingDropPeriod) }

    var interactionMode by remember { mutableStateOf(prefs.interactionMode) }
    var bubbleSize by remember { mutableFloatStateOf(prefs.bubbleSizeDp.toFloat()) }
    var bubbleOpacity by remember { mutableFloatStateOf(prefs.bubbleOpacity) }
    var isHaptic by remember { mutableStateOf(prefs.hapticFeedbackEnabled) }
    var showOnlyWithKeyboard by remember { mutableStateOf(prefs.showOnlyWithKeyboard) }
    var isMicDismissed by remember { mutableStateOf(FloatingBubbleService.instance?.isManuallyDismissed == true) }

    var dictionaryEntries by remember { mutableStateOf(dictionaryManager.getAllEntries()) }
    var snippetList by remember { mutableStateOf(snippetManager.getAllSnippets()) }
    var recentHistory by remember { mutableStateOf(historyManager.getRecentRecords()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionState by remember {
        mutableStateOf(PermissionHelper.checkAllPermissions(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                permissionState = PermissionHelper.checkAllPermissions(context)
                isServiceRunning = FloatingBubbleService.isRunning()
                isMicDismissed = FloatingBubbleService.instance?.isManuallyDismissed == true
                if (permissionState.allCriticalGranted && prefs.isConfigured) {
                    FloatingBubbleService.startIfPermitted(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionState.allCriticalGranted) {
        while (!permissionState.allCriticalGranted) {
            delay(800L)
            val latest = PermissionHelper.checkAllPermissions(context)
            if (latest != permissionState) {
                permissionState = latest
                isServiceRunning = FloatingBubbleService.isRunning()
                if (latest.allCriticalGranted && prefs.isConfigured) {
                    FloatingBubbleService.startIfPermitted(context)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            // Top Bar §5.1: Height 56dp, DarkCanvas background, "Flow" in Serif 22sp, 6dp Ochre dot when needed
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Flow",
                            fontFamily = FontFamily.Serif,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            color = DarkInk
                        )
                        if (!permissionState.allCriticalGranted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(OchreDark)
                            )
                        }
                    }
                },
                actions = {
                    // Segmented Language Control [ EN | বাংলা ] §5.1
                    FlowCompactSegmentedControl(
                        items = listOf("EN", "বাংলা"),
                        selectedIndex = if (isBn) 1 else 0,
                        onSelect = { idx ->
                            val newLang = if (idx == 1) "bn" else "en"
                            onLanguageChange(newLang)
                        }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkCanvas,
                    titleContentColor = DarkInk
                )
            )
        },
        bottomBar = {
            val accent = LocalAccentColor.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCanvas)
                    .navigationBarsPadding()
            ) {
                FlowHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Home Pill Tab (Issue 5 redesign)
                    val isHome = selectedTab == 0
                    val homePillBg by animateColorAsState(
                        targetValue = if (isHome) accent.copy(alpha = 0.15f) else Color.Transparent,
                        label = "home_pill_bg"
                    )
                    val homeContentColor by animateColorAsState(
                        targetValue = if (isHome) accent else DarkInkMuted,
                        label = "home_pill_color"
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(homePillBg)
                            .clickable { selectedTab = 0 }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = str("nav_home"),
                            tint = homeContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = str("nav_home"),
                            fontSize = 13.sp,
                            fontWeight = if (isHome) FontWeight.SemiBold else FontWeight.Medium,
                            color = homeContentColor
                        )
                    }

                    // History Pill Tab (Issue 5 redesign)
                    val isHistory = selectedTab == 1
                    val historyPillBg by animateColorAsState(
                        targetValue = if (isHistory) accent.copy(alpha = 0.15f) else Color.Transparent,
                        label = "hist_pill_bg"
                    )
                    val historyContentColor by animateColorAsState(
                        targetValue = if (isHistory) accent else DarkInkMuted,
                        label = "hist_pill_color"
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(historyPillBg)
                            .clickable {
                                recentHistory = historyManager.getRecentRecords()
                                selectedTab = 1
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = str("nav_history"),
                            tint = historyContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = str("nav_history"),
                            fontSize = 13.sp,
                            fontWeight = if (isHistory) FontWeight.SemiBold else FontWeight.Medium,
                            color = historyContentColor
                        )
                    }
                }
            }
        },
        containerColor = DarkCanvas
    ) { padding ->
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // 1. Hero Block §6.1
                item {
                    val headlineText = when {
                        !permissionState.allCriticalGranted -> str("status_setup")
                        isServiceRunning && !isMicDismissed -> str("status_ready")
                        else -> str("status_paused")
                    }
                    val captionText = when {
                        !permissionState.allCriticalGranted -> str("hero_caption_setup")
                        isServiceRunning && !isMicDismissed -> str("hero_caption_ready")
                        else -> str("hero_caption_paused")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = headlineText,
                                fontFamily = FontFamily.Serif,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Normal,
                                color = DarkInk
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = captionText,
                                fontSize = 13.sp,
                                color = DarkInkMuted,
                                lineHeight = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Live Hero Disc (56dp) drawn with drawDandaMic (§4.4 & §6.1)
                        val discColor = when {
                            !permissionState.allCriticalGranted -> DarkSurfaceRaised
                            isServiceRunning && !isMicDismissed -> DarkInk
                            else -> DarkSurfaceRaised
                        }
                        val glyphColor = when {
                            !permissionState.allCriticalGranted -> DarkInkMuted
                            isServiceRunning && !isMicDismissed -> DarkInkOnAccent
                            else -> DarkInkMuted
                        }

                        Canvas(
                            modifier = Modifier
                                .size(56.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (!permissionState.allCriticalGranted) {
                                        showPermissionsSheet = true
                                    } else {
                                        if (isServiceRunning) {
                                            val s = FloatingBubbleService.instance
                                            if (s != null) {
                                                if (s.isManuallyDismissed) s.showBubbleFromBottom()
                                                else s.hideBubbleDirectly()
                                                isMicDismissed = s.isManuallyDismissed
                                            }
                                        } else {
                                            FloatingBubbleService.startIfPermitted(context)
                                            isServiceRunning = true
                                            isMicDismissed = false
                                        }
                                    }
                                }
                        ) {
                            drawDandaMic(disc = discColor, glyph = glyphColor)
                        }
                    }
                }

                // 2. Show mic row (§6.1)
                item {
                    FlowGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = str("show_mic"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = DarkInk
                            )
                            Switch(
                                checked = isServiceRunning && !isMicDismissed,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        if (!permissionState.allCriticalGranted) {
                                            showPermissionsSheet = true
                                            return@Switch
                                        }
                                        if (!prefs.isConfigured) {
                                            Toast.makeText(context, str("save_key_first"), Toast.LENGTH_SHORT).show()
                                            activeKeySheetProvider = "Gemini"
                                            return@Switch
                                        }
                                        if (FloatingBubbleService.isRunning()) {
                                            FloatingBubbleService.instance?.showBubbleFromBottom()
                                        } else {
                                            FloatingBubbleService.startIfPermitted(context)
                                        }
                                        isServiceRunning = true
                                        isMicDismissed = false
                                    } else {
                                        FloatingBubbleService.instance?.hideBubbleDirectly()
                                        isMicDismissed = true
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkInkOnAccent,
                                    checkedTrackColor = DarkInk,
                                    uncheckedThumbColor = DarkInkMuted,
                                    uncheckedTrackColor = DarkSurfaceSunken,
                                    uncheckedBorderColor = DarkHairline
                                )
                            )
                        }

                        FlowHairline()

                        // 3. Setup row (§6.1)
                        val totalPerms = if (permissionState.isRestrictedSettingsPossible) 3 else 2
                        val donePerms = (if (permissionState.hasAccessibilityPermission) 1 else 0) +
                                (if (permissionState.hasOverlayPermission) 1 else 0) +
                                (if (permissionState.isRestrictedSettingsPossible && permissionState.hasAccessibilityPermission) 1 else 0)
                        val allDone = donePerms >= totalPerms

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { showPermissionsSheet = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = str("setup"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = DarkInk
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (allDone) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = SageDark,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "$totalPerms টির মধ্যে $donePerms সম্পন্ন" else "$donePerms of $totalPerms done",
                                        fontSize = 13.sp,
                                        color = SageDark
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(OchreDark)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isBn) "$donePerms সম্পন্ন" else "$donePerms of $totalPerms done",
                                        fontSize = 13.sp,
                                        color = OchreDark
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = DarkInkMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // 4. Dictation (Engine mode) §6.2
                item {
                    Text(
                        text = str("dictation"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DarkInkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    FlowGroup {
                        val modes = listOf(
                            Triple(EngineMode.CLOUD_AUDIO, str("engine_cloud"), str("engine_cloud_desc")),
                            Triple(EngineMode.HYBRID_CLOUD_POLISH, str("engine_fast"), str("engine_fast_desc")),
                            Triple(EngineMode.OFFLINE_LIGHTWEIGHT, str("engine_offline"), str("engine_offline_desc")),
                            Triple(EngineMode.OFFLINE_LOCAL_LLM, str("engine_offline_ai"), str("engine_offline_ai_desc"))
                        )

                        modes.forEachIndexed { idx, (mode, title, desc) ->
                            val isSelected = selectedEngineMode == mode
                            val bgCol by animateColorAsState(
                                targetValue = if (isSelected) DarkSurfaceRaised else Color.Transparent,
                                label = "engine_bg"
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bgCol)
                                    .clickable {
                                        selectedEngineMode = mode
                                        prefs.engineMode = mode
                                        notifyServiceSettingsChanged(context)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedEngineMode = mode
                                        prefs.engineMode = mode
                                        notifyServiceSettingsChanged(context)
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = DarkInk,
                                        unselectedColor = DarkInkMuted
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = title,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = DarkInk
                                        )
                                        if (mode == EngineMode.CLOUD_AUDIO) {
                                            Text(
                                                text = str("recommended"),
                                                fontSize = 11.sp,
                                                color = DarkInkMuted
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = desc,
                                        fontSize = 12.sp,
                                        color = DarkInkMuted,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            if (idx < modes.size - 1) {
                                FlowHairline()
                            }
                        }
                    }
                }

                // Offline Setup & Model Download Card (Issue 7)
                if (selectedEngineMode == EngineMode.OFFLINE_LIGHTWEIGHT) {
                    item {
                        Text(
                            text = if (isBn) "অফলাইন স্পিচ সেটআপ" else "Offline Speech Setup",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkInkMuted,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                        FlowGroup {
                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SettingsVoice,
                                        contentDescription = null,
                                        tint = LocalAccentColor.current,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (isBn) "Google Voice Typing অফলাইন প্যাক" else "Google Voice Typing Offline Pack",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = DarkInk
                                        )
                                        Text(
                                            text = if (isBn)
                                                "ডিভাইসে ইন্টারনেট ছাড়া বলতে বাংলা (বাংলাদেশ/ভারত) প্যাক ডাউনলোড প্রয়োজন।"
                                            else
                                                "Offline dictation requires the Bengali language pack in Google Voice Typing.",
                                            fontSize = 12.sp,
                                            color = DarkInkMuted,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                FlowButton(
                                    text = if (isBn) "ভয়েস টাইপিং সেটিংস খুলুন" else "Open Voice Typing Settings",
                                    icon = Icons.Default.Launch,
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                                        } catch (_: Exception) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Please open Settings -> Languages -> Google Voice Typing", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else if (selectedEngineMode == EngineMode.OFFLINE_LOCAL_LLM) {
                    item {
                        Text(
                            text = if (isBn) "অন-ডিভাইস এআই মডেল (Gemma 3)" else "On-Device AI Model (Gemma 3)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkInkMuted,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                        FlowGroup {
                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                val downloadState by modelDownloadManager.downloadState.collectAsState()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = LocalAccentColor.current,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Gemma 3 1B INT4 LiteRT",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = DarkInk
                                        )
                                        val stateDesc = when (val s = downloadState) {
                                            is ModelDownloadState.NotDownloaded ->
                                                if (isBn) "মডেল ডাউনলোড করা নেই (~৭৫০ MB)" else "Model not downloaded (~750 MB)"
                                            is ModelDownloadState.Downloading ->
                                                if (isBn) "ডাউনলোড হচ্ছে: ${s.downloadedMb} MB / ${s.totalMb} MB (${s.progressPercent}%)"
                                                else "Downloading: ${s.downloadedMb} MB / ${s.totalMb} MB (${s.progressPercent}%)"
                                            is ModelDownloadState.Downloaded ->
                                                if (isBn) "মডেল প্রস্তুত (${s.sizeMb} MB) • ১০০% প্রাইভেট" else "Model ready (${s.sizeMb} MB) • 100% private"
                                            is ModelDownloadState.Error ->
                                                if (isBn) "ত্রুটি: ${s.message}" else "Error: ${s.message}"
                                        }
                                        Text(
                                            text = stateDesc,
                                            fontSize = 12.sp,
                                            color = when (downloadState) {
                                                is ModelDownloadState.Downloaded -> SageDark
                                                is ModelDownloadState.Error -> VermilionDark
                                                else -> DarkInkMuted
                                            },
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                                if (downloadState is ModelDownloadState.Downloading) {
                                    val prog = (downloadState as ModelDownloadState.Downloading).progressPercent / 100f
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { prog },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = LocalAccentColor.current,
                                        trackColor = DarkSurfaceRaised
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                when (downloadState) {
                                    is ModelDownloadState.NotDownloaded, is ModelDownloadState.Error -> {
                                        FlowButton(
                                            text = if (isBn) "মডেল ডাউনলোড করুন (~৭৫০ MB)" else "Download Model (~750 MB)",
                                            icon = Icons.Default.Download,
                                            onClick = {
                                                scope.launch {
                                                    modelDownloadManager.downloadModel()
                                                }
                                            }
                                        )
                                    }
                                    is ModelDownloadState.Downloading -> {
                                        FlowButton(
                                            text = if (isBn) "ডাউনলোড বাতিল করুন" else "Cancel Download",
                                            icon = Icons.Default.Close,
                                            onClick = {
                                                modelDownloadManager.cancelDownload()
                                            }
                                        )
                                    }
                                    is ModelDownloadState.Downloaded -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SageDark, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isBn) "মডেল ইন্সটল করা আছে" else "Model Installed",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = SageDark
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    modelDownloadManager.deleteDownloadedModel()
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = VermilionDark
                                                ),
                                                border = BorderStroke(1.dp, VermilionDark.copy(alpha = 0.5f)),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(
                                                    text = if (isBn) "মুছে ফেলুন" else "Delete",
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Keys Group §6.3
                item {
                    Text(
                        text = str("keys"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DarkInkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    FlowGroup {
                        val keyRows = listOf(
                            Pair("Gemini", geminiKeyText.isNotBlank()),
                            Pair("Groq", groqKeyText.isNotBlank()),
                            Pair("Cerebras", cerebrasKeyText.isNotBlank()),
                            Pair("OpenRouter", openRouterKeyText.isNotBlank())
                        )

                        keyRows.forEachIndexed { idx, (provider, isAdded) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clickable { activeKeySheetProvider = provider },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = provider,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = DarkInk
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isAdded) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = SageDark,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = str("added"),
                                            fontSize = 12.sp,
                                            color = SageDark
                                        )
                                    } else {
                                        Text(
                                            text = str("not_added"),
                                            fontSize = 12.sp,
                                            color = DarkInkMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = DarkInkMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (idx < keyRows.size - 1) {
                                FlowHairline()
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Test Connection Button (§6.3)
                    Button(
                        onClick = {
                            if (prefs.geminiApiKey.isBlank()) {
                                Toast.makeText(context, str("save_key_first"), Toast.LENGTH_SHORT).show()
                                activeKeySheetProvider = "Gemini"
                                return@Button
                            }
                            isTestingConnection = true
                            scope.launch {
                                try {
                                    val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        ConnectivityLabRunner.runThreeStageTest(
                                            context = context,
                                            http = okhttp3.OkHttpClient(),
                                            rawApiKey = prefs.geminiApiKey,
                                            latestRecordedFile = null
                                        )
                                    }
                                    connectivityReport = report
                                    isTestingConnection = false
                                    showDiagnosticsDialog = true
                                } catch (t: Throwable) {
                                    isTestingConnection = false
                                    Toast.makeText(context, "Test failed: ${t.localizedMessage ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkInk,
                            contentColor = DarkInkOnAccent
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(
                            text = if (isTestingConnection) str("testing") else str("test_connection"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 6. Voice and Polish Group §6.4
                item {
                    Text(
                        text = str("voice_and_polish"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DarkInkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    FlowGroup {
                        // Row 1: Smart Polish
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str("smart_polish"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(str("smart_polish_desc"), fontSize = 12.sp, color = DarkInkMuted, lineHeight = 16.sp)
                            }
                            Switch(
                                checked = isSmartPolish,
                                onCheckedChange = {
                                    isSmartPolish = it
                                    prefs.smartPolishEnabled = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkInkOnAccent,
                                    checkedTrackColor = DarkInk,
                                    uncheckedThumbColor = DarkInkMuted,
                                    uncheckedTrackColor = DarkSurfaceSunken,
                                    uncheckedBorderColor = DarkHairline
                                )
                            )
                        }

                        FlowHairline()

                        // Row 2: Clean-up Segmented Control (§6.4)
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(str("cleanup"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Spacer(modifier = Modifier.height(10.dp))

                            val cleanupLevels = listOf("Off", "Light", "Full")
                            val currentCleanupIdx = when (autoCleanupLevel) {
                                AutoCleanupLevel.NONE -> 0
                                AutoCleanupLevel.LIGHT -> 1
                                AutoCleanupLevel.MEDIUM -> 2
                            }

                            FlowSegmentedControl(
                                items = cleanupLevels,
                                selectedIndex = currentCleanupIdx,
                                onSelect = { idx ->
                                    val newLevel = when (idx) {
                                        0 -> AutoCleanupLevel.NONE
                                        1 -> AutoCleanupLevel.LIGHT
                                        else -> AutoCleanupLevel.MEDIUM
                                    }
                                    autoCleanupLevel = newLevel
                                    prefs.autoCleanupLevel = newLevel
                                }
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            val cleanupDesc = when (autoCleanupLevel) {
                                AutoCleanupLevel.NONE -> str("cleanup_off_desc")
                                AutoCleanupLevel.LIGHT -> str("cleanup_light_desc")
                                AutoCleanupLevel.MEDIUM -> str("cleanup_full_desc")
                            }
                            Text(cleanupDesc, fontSize = 12.sp, color = DarkInkMuted)
                        }

                        FlowHairline()

                        // Row 3: Drop final full stop in chats
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str("drop_period"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(str("drop_period_desc"), fontSize = 12.sp, color = DarkInkMuted, lineHeight = 16.sp)
                            }
                            Switch(
                                checked = casualMessagingDropPeriod,
                                onCheckedChange = {
                                    casualMessagingDropPeriod = it
                                    prefs.casualMessagingDropPeriod = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkInkOnAccent,
                                    checkedTrackColor = DarkInk,
                                    uncheckedThumbColor = DarkInkMuted,
                                    uncheckedTrackColor = DarkSurfaceSunken,
                                    uncheckedBorderColor = DarkHairline
                                )
                            )
                        }
                    }
                }

                // 7. Microphone Group §6.5
                item {
                    Text(
                        text = str("microphone"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DarkInkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    FlowGroup {
                        // Live Preview Block (§6.5: 96dp tall, centered preview)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .padding(top = 10.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(bubbleSize.dp)) {
                                drawDandaMic(disc = DarkInk.copy(alpha = bubbleOpacity), glyph = DarkInkOnAccent)
                            }
                            Text(
                                text = "Preview",
                                fontSize = 10.sp,
                                color = DarkInkMuted,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 4.dp)
                            )
                        }

                        FlowHairline()

                        // Row 1: Interaction Segmented Control
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(str("how_to_start"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Spacer(modifier = Modifier.height(8.dp))

                            FlowSegmentedControl(
                                items = listOf(str("tap_to_start"), str("hold_to_talk")),
                                selectedIndex = if (interactionMode == InteractionMode.TAP_TO_TALK) 0 else 1,
                                onSelect = { idx ->
                                    val mode = if (idx == 0) InteractionMode.TAP_TO_TALK else InteractionMode.PUSH_TO_TALK
                                    interactionMode = mode
                                    prefs.interactionMode = mode
                                }
                            )
                        }

                        FlowHairline()

                        // Row 2: Size Slider (48..84dp, step 4dp per §6.5)
                        Column(modifier = Modifier.padding(vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(str("size"), fontSize = 14.sp, color = DarkInk)
                                Text("${bubbleSize.toInt()} dp", fontSize = 13.sp, color = DarkInkMuted)
                            }
                            Slider(
                                value = bubbleSize,
                                onValueChange = { raw ->
                                    // Snap to 4dp step grid: 48, 52, 56, 60, 64, 68, 72, 76, 80, 84
                                    val stepped = (Math.round((raw - 48f) / 4f) * 4f) + 48f
                                    bubbleSize = stepped.coerceIn(48f, 84f)
                                    prefs.bubbleSizeDp = bubbleSize.toInt()
                                    notifyServiceSettingsChanged(context)
                                },
                                valueRange = 48f..84f,
                                steps = 8, // (84 - 48) / 4 - 1 = 8 steps
                                colors = SliderDefaults.colors(
                                    thumbColor = DarkInk,
                                    activeTrackColor = DarkInk,
                                    inactiveTrackColor = DarkSurfaceSunken
                                )
                            )
                        }

                        FlowHairline()

                        // Row 3: Opacity Slider (30..100%, step 5% per §6.5)
                        Column(modifier = Modifier.padding(vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(str("opacity"), fontSize = 14.sp, color = DarkInk)
                                Text("${(bubbleOpacity * 100).toInt()}%", fontSize = 13.sp, color = DarkInkMuted)
                            }
                            Slider(
                                value = bubbleOpacity,
                                onValueChange = { raw ->
                                    // Snap to 5% step grid
                                    val percent = Math.round(raw * 100f / 5f) * 5f
                                    bubbleOpacity = (percent / 100f).coerceIn(0.30f, 1.0f)
                                    prefs.bubbleOpacity = bubbleOpacity
                                    notifyServiceSettingsChanged(context)
                                },
                                valueRange = 0.30f..1.0f,
                                steps = 13, // (100 - 30) / 5 - 1 = 13 steps
                                colors = SliderDefaults.colors(
                                    thumbColor = DarkInk,
                                    activeTrackColor = DarkInk,
                                    inactiveTrackColor = DarkSurfaceSunken
                                )
                            )
                        }

                        FlowHairline()

                        // Row 4: Haptic Feedback
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str("haptic"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(str("haptic_desc"), fontSize = 12.sp, color = DarkInkMuted)
                            }
                            Switch(
                                checked = isHaptic,
                                onCheckedChange = {
                                    isHaptic = it
                                    prefs.hapticFeedbackEnabled = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkInkOnAccent,
                                    checkedTrackColor = DarkInk,
                                    uncheckedThumbColor = DarkInkMuted,
                                    uncheckedTrackColor = DarkSurfaceSunken,
                                    uncheckedBorderColor = DarkHairline
                                )
                            )
                        }

                        FlowHairline()

                        // Row 5: Show only when typing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str("show_only_typing"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(str("show_only_typing_desc"), fontSize = 12.sp, color = DarkInkMuted)
                            }
                            Switch(
                                checked = showOnlyWithKeyboard,
                                onCheckedChange = {
                                    showOnlyWithKeyboard = it
                                    prefs.showOnlyWithKeyboard = it
                                    FloatingBubbleService.instance?.applyVisibilityRule()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkInkOnAccent,
                                    checkedTrackColor = DarkInk,
                                    uncheckedThumbColor = DarkInkMuted,
                                    uncheckedTrackColor = DarkSurfaceSunken,
                                    uncheckedBorderColor = DarkHairline
                                )
                            )
                        }

                        FlowHairline()

                        // Trigger Style Section (§3 Gate Prompt 3)
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(str("trigger_style"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Spacer(modifier = Modifier.height(8.dp))

                            val triggerStyles: List<Pair<String, String>> = listOf(
                                PreferencesManager.TRIGGER_STYLE_BOTH to str("trigger_both"),
                                PreferencesManager.TRIGGER_STYLE_KEYBOARD_ONLY to str("trigger_keyboard_only"),
                                PreferencesManager.TRIGGER_STYLE_FLOATING_ONLY to str("trigger_floating_only")
                            )

                            triggerStyles.forEach { item ->
                                val style = item.first
                                val label = item.second
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            triggerStyle = style
                                            prefs.triggerStyle = style
                                            FloatingBubbleService.instance?.applyVisibilityRule()
                                            TextInjectorAccessibilityService.instance?.syncKeyboardVisibilityWithBubble()
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = triggerStyle == style,
                                        onClick = {
                                            triggerStyle = style
                                            prefs.triggerStyle = style
                                            FloatingBubbleService.instance?.applyVisibilityRule()
                                            TextInjectorAccessibilityService.instance?.syncKeyboardVisibilityWithBubble()
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = DarkInk,
                                            unselectedColor = DarkInkMuted
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = label,
                                        fontSize = 14.sp,
                                        color = DarkInk
                                    )
                                }
                            }
                        }

                        FlowHairline()

                        // Row 6: Hide or Show now button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isMicDismissed) {
                                        FloatingBubbleService.instance?.showBubbleFromBottom()
                                        isMicDismissed = false
                                    } else {
                                        FloatingBubbleService.instance?.hideBubbleDirectly()
                                        isMicDismissed = true
                                    }
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isMicDismissed) str("show_mic") else str("hide_mic"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DarkInk
                            )
                        }
                    }
                }

                // 8. Vocabulary Group §6.6
                item {
                    Text(
                        text = str("vocabulary"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DarkInkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    FlowGroup {
                        // Personal dictionary row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { showDictionarySheet = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str("personal_dict"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(str("personal_dict_desc"), fontSize = 12.sp, color = DarkInkMuted)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${dictionaryEntries.size}", fontSize = 13.sp, color = DarkInkMuted)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DarkInkMuted, modifier = Modifier.size(16.dp))
                            }
                        }

                        FlowHairline()

                        // Voice snippets row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { showSnippetsSheet = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str("snippets"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(str("snippets_desc"), fontSize = 12.sp, color = DarkInkMuted)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${snippetList.size}", fontSize = 13.sp, color = DarkInkMuted)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DarkInkMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // 9. About Group §6.10
                item {
                    Text(
                        text = str("about"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DarkInkMuted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    FlowGroup {
                        // Appearance Segmented Control
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(str("appearance"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Spacer(modifier = Modifier.height(8.dp))
                            val themes = listOf("System", "Dark", "Light")
                            val themeIdx = when (currentAppearance) {
                                "system" -> 0
                                "dark" -> 1
                                else -> 2
                            }
                            FlowSegmentedControl(
                                items = themes,
                                selectedIndex = themeIdx,
                                onSelect = { idx ->
                                    val newT = when (idx) {
                                        0 -> "system"
                                        1 -> "dark"
                                        else -> "light"
                                    }
                                    onAppearanceChange(newT)
                                }
                            )
                        }

                        FlowHairline()

                        // Accent Color Palette Selector (Issue 1)
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn) "প্রিমিয়াম অ্যাকসেন্ট কালার" else "Accent Color",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = DarkInk
                                )
                                val activeAccent = AvailableAccentOptions.firstOrNull { it.key == selectedAccentKey }
                                val activeAccentName = if (activeAccent != null) {
                                    if (isBn) activeAccent.nameBn else activeAccent.nameEn
                                } else "Classic Ivory"
                                Text(
                                    text = activeAccentName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LocalAccentColor.current
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AvailableAccentOptions.forEach { opt ->
                                    val isChosen = selectedAccentKey == opt.key
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(opt.color)
                                            .border(
                                                width = if (isChosen) 2.5.dp else 1.dp,
                                                color = if (isChosen) DarkInk else DarkHairline,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                selectedAccentKey = opt.key
                                                prefs.accentColorKey = opt.key
                                                onAccentChange(opt.key)
                                                notifyServiceSettingsChanged(context)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isChosen) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = opt.nameEn,
                                                tint = if (opt.key == "ivory") DarkCanvas else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        FlowHairline()

                        // Guide Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable { showGuideDialog = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(str("guide"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DarkInkMuted, modifier = Modifier.size(16.dp))
                        }

                        FlowHairline()

                        // Diagnostics Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable { showDiagnosticsDialog = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(str("diagnostics"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = DarkInkMuted, modifier = Modifier.size(16.dp))
                        }

                        FlowHairline()

                        // Version
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Flow", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                            Text(str("version"), fontSize = 13.sp, color = DarkInkMuted)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        } else {
            // History Tab §6.7
            HistoryScreen(
                history = recentHistory,
                isBn = isBn,
                onCopy = { text ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Flow", text))
                },
                onClear = {
                    historyManager.clearHistory()
                    recentHistory = emptyList()
                }
            )
        }
    }

    // Setup Permissions Sheet (§6.9)
    if (showPermissionsSheet) {
        SetupPermissionsSheet(
            permissionState = permissionState,
            isBn = isBn,
            onDismiss = { showPermissionsSheet = false },
            onRequestAccessibility = onRequestAccessibilityPermission,
            onRequestOverlay = onRequestOverlayPermission,
            onRequestXiaomiAutostart = onRequestXiaomiAutostart,
            onRequestBatteryExemption = onRequestBatteryExemption,
            onRequestSamsungDeviceCare = onRequestSamsungDeviceCare,
            onRequestXiaomiLockApps = onRequestXiaomiLockApps
        )
    }

    // Key Sheet (§6.3)
    activeKeySheetProvider?.let { provider ->
        val currentKey = when (provider) {
            "Gemini" -> geminiKeyText
            "Groq" -> groqKeyText
            "Cerebras" -> cerebrasKeyText
            else -> openRouterKeyText
        }

        KeyBottomSheet(
            providerName = provider,
            initialKey = currentKey,
            onSave = { savedKey ->
                when (provider) {
                    "Gemini" -> {
                        geminiKeyText = savedKey
                        prefs.geminiApiKey = savedKey
                    }
                    "Groq" -> {
                        groqKeyText = savedKey
                        prefs.groqApiKey = savedKey
                    }
                    "Cerebras" -> {
                        cerebrasKeyText = savedKey
                        prefs.cerebrasApiKey = savedKey
                    }
                    "OpenRouter" -> {
                        openRouterKeyText = savedKey
                        prefs.openRouterApiKey = savedKey
                    }
                }
                notifyServiceSettingsChanged(context)
            },
            onPaste = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            },
            onDismiss = { activeKeySheetProvider = null },
            isBn = isBn
        )
    }

    // Guide Dialog (§6.10)
    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = {
                Text("Flow Guide", fontFamily = FontFamily.Serif, fontSize = 20.sp, color = DarkInk)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("1. Free Gemini Key (Recommended)", fontWeight = FontWeight.Bold, color = DarkInk)
                    Text(
                        "Visit aistudio.google.com to generate a free Gemini API key. Paste it under Keys to enjoy unlimited Bangla and English dictation.",
                        fontSize = 13.sp,
                        color = DarkInkMuted,
                        lineHeight = 18.sp
                    )

                    FlowHairline()

                    Text("2. Offline Voice Typing", fontWeight = FontWeight.Bold, color = DarkInk)
                    Text(
                        "Download Bengali (Bangladesh) and English voice packs in Android Settings > Google Voice Typing to dictate even when disconnected.",
                        fontSize = 13.sp,
                        color = DarkInkMuted,
                        lineHeight = 18.sp
                    )

                    FlowHairline()

                    Text("3. Double-Tap Language Switch", fontWeight = FontWeight.Bold, color = DarkInk)
                    Text(
                        "Double-tap the floating mic while idle to swap between Bangla (বাং) and English (EN).",
                        fontSize = 13.sp,
                        color = DarkInkMuted,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showGuideDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkInk, contentColor = DarkInkOnAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Got it")
                }
            },
            containerColor = DarkSurface
        )
    }

    // Diagnostics Dialog (§6.8)
    if (showDiagnosticsDialog) {
        val report = connectivityReport
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = {
                Text(str("diagnostics"), fontFamily = FontFamily.Serif, fontSize = 20.sp, color = DarkInk)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (report == null) {
                        Text("No test report yet. Run 'Test connection' to verify your Gemini setup.", fontSize = 13.sp, color = DarkInkMuted)
                    } else {
                        val stages = listOf(
                            Pair("Key & models", report.s1Status),
                            Pair("File upload", report.s2Status),
                            Pair("Live transcription", report.s3Status)
                        )

                        stages.forEach { (name, status) ->
                            val isPass = status.startsWith("PASS")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontSize = 14.sp, color = DarkInk)
                                if (isPass) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = SageDark, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Passed", fontSize = 12.sp, color = SageDark)
                                    }
                                } else {
                                    Text("Failed", fontSize = 12.sp, color = DarkInkMuted)
                                }
                            }
                            Text(status, fontSize = 11.sp, color = DarkInkMuted, fontFamily = FontFamily.Monospace)
                            FlowHairline()
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showDiagnosticsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkInk, contentColor = DarkInkOnAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                if (report != null) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Flow Diagnostics", report.redactedSummary))
                        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy report", color = DarkInkMuted)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }

    // Personal Dictionary Sheet (§6.6)
    if (showDictionarySheet) {
        var wordText by remember { mutableStateOf("") }
        var misText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showDictionarySheet = false },
            title = {
                Text(str("personal_dict"), fontFamily = FontFamily.Serif, fontSize = 20.sp, color = DarkInk)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(str("personal_dict_desc"), fontSize = 12.sp, color = DarkInkMuted)

                    // Add new word
                    TextField(
                        value = wordText,
                        onValueChange = { wordText = it },
                        placeholder = { Text("Correct Word (e.g. Sylhet)", fontSize = 13.sp, color = DarkInkMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceSunken,
                            unfocusedContainerColor = DarkSurfaceSunken,
                            focusedTextColor = DarkInk,
                            unfocusedTextColor = DarkInk
                        ),
                        singleLine = true
                    )
                    TextField(
                        value = misText,
                        onValueChange = { misText = it },
                        placeholder = { Text("Misspelling (optional, e.g. Silhet)", fontSize = 13.sp, color = DarkInkMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceSunken,
                            unfocusedContainerColor = DarkSurfaceSunken,
                            focusedTextColor = DarkInk,
                            unfocusedTextColor = DarkInk
                        ),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            if (wordText.isNotBlank()) {
                                dictionaryManager.addOrUpdateEntry(wordText, misText.ifBlank { null })
                                dictionaryEntries = dictionaryManager.getAllEntries()
                                wordText = ""
                                misText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkInk, contentColor = DarkInkOnAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add word")
                    }

                    FlowHairline()

                    // Entries List
                    if (dictionaryEntries.isEmpty()) {
                        Text("Words you correct will appear here.", fontSize = 13.sp, color = DarkInkMuted, textAlign = TextAlign.Center)
                    } else {
                        dictionaryEntries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.word, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                    if (!entry.misspelling.isNullOrBlank()) {
                                        Text(entry.misspelling, fontSize = 12.sp, color = DarkInkMuted)
                                    }
                                }
                                if (entry.autoLearned) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(OchreDark))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Learned", fontSize = 11.sp, color = OchreDark)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        dictionaryManager.deleteEntry(entry.word, entry.misspelling)
                                        dictionaryEntries = dictionaryManager.getAllEntries()
                                    }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = DarkInkMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showDictionarySheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkInk, contentColor = DarkInkOnAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done")
                }
            },
            containerColor = DarkSurface
        )
    }

    // Voice Snippets Sheet (§6.6)
    if (showSnippetsSheet) {
        var triggerText by remember { mutableStateOf("") }
        var expansionText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSnippetsSheet = false },
            title = {
                Text(str("snippets"), fontFamily = FontFamily.Serif, fontSize = 20.sp, color = DarkInk)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(str("snippets_desc"), fontSize = 12.sp, color = DarkInkMuted)

                    TextField(
                        value = triggerText,
                        onValueChange = { triggerText = it },
                        placeholder = { Text("Spoken trigger (e.g. my email)", fontSize = 13.sp, color = DarkInkMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceSunken,
                            unfocusedContainerColor = DarkSurfaceSunken,
                            focusedTextColor = DarkInk,
                            unfocusedTextColor = DarkInk
                        ),
                        singleLine = true
                    )
                    TextField(
                        value = expansionText,
                        onValueChange = { expansionText = it },
                        placeholder = { Text("Expanded text block", fontSize = 13.sp, color = DarkInkMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceSunken,
                            unfocusedContainerColor = DarkSurfaceSunken,
                            focusedTextColor = DarkInk,
                            unfocusedTextColor = DarkInk
                        ),
                        maxLines = 3
                    )

                    Button(
                        onClick = {
                            if (triggerText.isNotBlank() && expansionText.isNotBlank()) {
                                snippetManager.addOrUpdateSnippet(triggerText, expansionText)
                                snippetList = snippetManager.getAllSnippets()
                                triggerText = ""
                                expansionText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkInk, contentColor = DarkInkOnAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add snippet")
                    }

                    FlowHairline()

                    if (snippetList.isEmpty()) {
                        Text("Short phrases you say often, like an address or email, go here.", fontSize = 13.sp, color = DarkInkMuted)
                    } else {
                        snippetList.forEach { snip ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(snip.trigger, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DarkInk)
                                    Text(snip.expansion, fontSize = 12.sp, color = DarkInkMuted, maxLines = 2)
                                }
                                IconButton(
                                    onClick = {
                                        snippetManager.deleteSnippet(snip.trigger)
                                        snippetList = snippetManager.getAllSnippets()
                                    }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = DarkInkMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSnippetsSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkInk, contentColor = DarkInkOnAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done")
                }
            },
            containerColor = DarkSurface
        )
    }
}

private fun notifyServiceSettingsChanged(context: Context) {
    if (FloatingBubbleService.isRunning()) {
        val intent = Intent(context, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_REFRESH_SETTINGS
        }
        context.startService(intent)
    }
}
