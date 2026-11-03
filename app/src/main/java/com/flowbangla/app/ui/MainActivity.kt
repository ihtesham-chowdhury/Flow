package com.flowbangla.app.ui

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flowbangla.app.FlowBanglaApp
import com.flowbangla.app.diagnostics.ConnectivityLabReport
import com.flowbangla.app.diagnostics.ConnectivityLabRunner
import com.flowbangla.app.model.CloudPolishProvider
import com.flowbangla.app.model.DictationRecord
import com.flowbangla.app.model.EngineMode
import com.flowbangla.app.model.InteractionMode
import com.flowbangla.app.model.SttLanguage
import com.flowbangla.app.offline.ModelDownloadManager
import com.flowbangla.app.offline.ModelDownloadState
import com.flowbangla.app.service.FloatingBubbleService
import com.flowbangla.app.service.TextInjectorAccessibilityService
import com.flowbangla.app.ui.theme.FlowBanglaTheme
import com.flowbangla.app.ui.theme.PrimaryBlue
import com.flowbangla.app.ui.theme.PrimaryBlueVariant
import com.flowbangla.app.ui.theme.AccentCyan
import com.flowbangla.app.ui.theme.SuccessGreen
import com.flowbangla.app.ui.theme.RecordingRed
import com.flowbangla.app.ui.theme.ProcessingAmber
import com.flowbangla.app.ui.theme.BackgroundDark
import com.flowbangla.app.ui.theme.SurfaceDark
import com.flowbangla.app.ui.theme.SurfaceCard
import com.flowbangla.app.ui.theme.TextPrimary
import com.flowbangla.app.ui.theme.TextSecondary
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
            FlowBanglaTheme {
                MainScreen(
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestAccessibilityPermission = { requestAccessibilityPermission() },
                    onRequestXiaomiAutostart = { requestXiaomiAutostart() },
                    onRequestBatteryExemption = { requestBatteryOptimizationExemption() }
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
        try {
            val intent = Intent().apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }
    }
}

// Localization dictionary for seamless English <-> Bangla switching
object L10n {
    fun t(key: String, isBn: Boolean): String {
        return if (isBn) bnMap[key] ?: enMap[key] ?: key else enMap[key] ?: key
    }

    private val enMap = mapOf(
        "app_title" to "Flow",
        "app_subtitle" to "AI Voice Dictation & Smart Polish",
        "nav_home" to "Home",
        "nav_history" to "History",
        "master_on" to "Floating Mic is Active",
        "master_off" to "Floating Mic is Off",
        "master_desc_on" to "Floating button is active above keyboard. Tap to dictate.",
        "master_desc_off" to "Turn on the switch to start the floating mic.",
        "api_title" to "Gemini API Key (Cloud Audio)",
        "api_desc" to "Enter your free Gemini API key from aistudio.google.com for unlimited daily dictation.",
        "api_hint" to "Gemini API Key",
        "paste" to "Paste",
        "test" to "Test Key",
        "test_success" to "✅ Connection Successful! Ready to dictate.",
        "test_fail" to "❌ Invalid API key or network error.",
        "perm_all_good" to "All Required Permissions Granted",
        "perm_needed" to "Permissions & Xiaomi Setup",
        "perm_desc" to "Required to display floating button above keyboard and insert text directly:",
        "perm_overlay" to "1. Display Pop-up Window (Overlay)",
        "perm_accessibility" to "2. Accessibility (Auto-typing)",
        "perm_autostart" to "3. Xiaomi Autostart (Prevent killing)",
        "perm_battery" to "4. Battery Saver (No restrictions)",
        "enable" to "Enable",
        "settings_title" to "Voice & Polish Settings",
        "smart_polish" to "Smart Polish (Wispr Flow style)",
        "smart_polish_desc" to "Removes fillers (um/like/মানে) and adds punctuation (., ? / । , ?).",
        "touch_mode" to "Microphone Interaction Mode:",
        "mode_tap" to "Single Tap (Start/Stop)",
        "mode_push" to "Hold to Speak",
        "haptic" to "Haptic Feedback",
        "haptic_desc" to "Vibrate on recording start and stop.",
        "bubble_size" to "Floating Button Size:",
        "bubble_opacity" to "Button Opacity / Transparency:",
        "mic_visibility" to "Floating Button Visibility",
        "mic_hidden_desc" to "Mic button is hidden. Tap here or notification to restore.",
        "mic_visible_desc" to "Drag mic to bottom screen target (✕) to hide, or toggle here.",
        "show_mic" to "Show Mic",
        "hide_mic" to "Hide Mic",
        "only_keyboard" to "Show only when typing (Wispr Flow style)",
        "only_keyboard_desc" to "Automatically shows button when keyboard/input is active, and hides it when not in use.",
        "history_title" to "Recent Dictations",
        "clear_history" to "Clear All",
        "copy" to "Copy",
        "copied" to "Text copied to clipboard",
        "no_history" to "No dictation history yet",
        "save_key_first" to "Please save required API Key first",
        "seconds" to "sec",
        "engine_mode_title" to "Engine Mode",
        "engine_mode_desc" to "Choose between cloud audio, hybrid on-device STT, or 100% offline modes.",
        "lang_gesture_title" to "Language & Gestures",
        "default_stt_lang" to "Default Dictation Language:",
        "double_tap_toggle" to "Double-tap floating mic to switch language",
        "double_tap_toggle_desc" to "Double-tap button while idle to toggle between বাংলা (বাং) and English (EN).",
        "live_preview" to "Live Transcription Preview",
        "live_preview_desc" to "Shows words as you speak. Keep OFF for lowest CPU and zero lag on Xiaomi phones.",
        "offline_voice_setup_btn" to "Open Google Voice Typing Settings",
        "offline_voice_setup_desc" to "Download বাংলা (বাংলাদেশ) & English offline voice packs for instant offline STT.",
        "provider_title" to "Cloud Polish Provider (Hybrid Mode)",
        "provider_desc" to "Choose which ultra-fast LLM cleans fillers and adds punctuation.",
        "model_downloader_title" to "On-Device Gemma 3 (1B) Model",
        "model_not_downloaded" to "Gemma 3 model not downloaded (~750 MB).",
        "model_downloading" to "Downloading Gemma 3 LiteRT model...",
        "model_downloaded" to "Gemma 3 model is downloaded and ready for 100% offline AI formatting.",
        "download_model_btn" to "Download Gemma 3 (~750 MB)",
        "cancel_download_btn" to "Cancel",
        "delete_model_btn" to "Delete Model",
        "hf_token_hint" to "Hugging Face Token (optional)",
        "help_btn" to "Setup Guide"
    )

    private val bnMap = mapOf(
        "app_title" to "Flow",
        "app_subtitle" to "এআই ভয়েস ডিক্টেশন ও স্মার্ট পলিশ",
        "nav_home" to "হোম",
        "nav_history" to "হিস্ট্রি",
        "master_on" to "ফ্লোটিং মাইক চালু আছে",
        "master_off" to "ফ্লোটিং মাইক বন্ধ আছে",
        "master_desc_on" to "কিবোর্ডের উপরে বাটনটি ভেসে থাকবে। কথা বলতে ট্যাপ করুন।",
        "master_desc_off" to "চালু করতে ডানের সুইচটি অন করুন।",
        "api_title" to "Gemini API Key (ক্লাউড অডিও)",
        "api_desc" to "aistudio.google.com থেকে আপনার ফ্রি API কী দিন। এটি সম্পূর্ণ ফ্রি ও সীমাহীন ব্যবহারের উপযোগী।",
        "api_hint" to "Gemini API Key",
        "paste" to "পেস্ট করুন",
        "test" to "যাচাই করুন",
        "test_success" to "✅ কানেকশন সফল হয়েছে!",
        "test_fail" to "❌ ভুল API Key অথবা নেটওয়ার্ক সমস্যা।",
        "perm_all_good" to "প্রয়োজনীয় সকল পারমিশন সক্রিয় আছে",
        "perm_needed" to "Xiaomi (MIUI) সেটাপ ও পারমিশন প্রয়োজন",
        "perm_desc" to "কিবোর্ডের উপর ভাসমান রাখা এবং স্বয়ংক্রিয়ভাবে লেখার জন্য নিচের অপশনগুলো চালু করুন:",
        "perm_overlay" to "১. ফ্লোটিং উইন্ডো (Display pop-up)",
        "perm_accessibility" to "২. অ্যাক্সেসিবিলিটি (অটো-টাইপিং)",
        "perm_autostart" to "৩. Xiaomi Autostart (ব্যাকগ্রাউন্ড বন্ধ হওয়া রোধ)",
        "perm_battery" to "৪. ব্যাটারি সেভার বন্ধ (No restrictions)",
        "enable" to "অন করুন",
        "settings_title" to "ভয়েস ও ফরম্যাটিং সেটিংস",
        "smart_polish" to "স্মার্ট পলিশ (Wispr Flow স্টাইল)",
        "smart_polish_desc" to "তোতলামি ও 'উম/মানে' বাদ দিয়ে দাঁড়ি, কমা ও প্রশ্নবোধক চিহ্ন যোগ করে।",
        "touch_mode" to "বাটন স্পর্শ করার ধরন:",
        "mode_tap" to "১ বার ট্যাপ (শুরু/থামুন)",
        "mode_push" to "চেপে ধরে কথা বলুন",
        "haptic" to "হ্যাপটিক ভাইব্রেশন",
        "haptic_desc" to "রেকর্ডিং শুরু ও শেষ হলে মৃদু ভাইব্রেশন দেবে।",
        "bubble_size" to "ফ্লোটিং বাটনের সাইজ:",
        "bubble_opacity" to "বাটনের স্বচ্ছতা (Opacity):",
        "mic_visibility" to "ভাসমান মাইকের দৃশ্যমানতা",
        "mic_hidden_desc" to "মাইক লুকানো আছে। দেখতে এখানে অথবা নোটিফিকেশনে ট্যাপ করুন।",
        "mic_visible_desc" to "স্ক্রিনের নিচে (✕) টেনে লুকান অথবা এখানে ট্যাপ করুন।",
        "show_mic" to "মাইক দেখান",
        "hide_mic" to "মাইক লুকান",
        "only_keyboard" to "শুধুমাত্র কিবোর্ড চালু থাকলে বাটন দেখাবে",
        "only_keyboard_desc" to "টাইপ করার সময় স্বয়ংক্রিয়ভাবে বাটন ভেসে উঠবে এবং অন্য সময় লুকিয়ে থাকবে।",
        "history_title" to "সাম্প্রতিক ডিক্টেশন হিস্ট্রি",
        "clear_history" to "মুছে ফেলুন",
        "copy" to "কপি করুন",
        "copied" to "টেক্সট কপি করা হয়েছে",
        "no_history" to "এখনও কোনো ডিক্টেশন রেকর্ড নেই",
        "save_key_first" to "আগে প্রয়োজনীয় API Key সেভ করুন",
        "seconds" to "সেকেন্ড",
        "engine_mode_title" to "ডিক্টেশন ইঞ্জিন ও মোড",
        "engine_mode_desc" to "ক্লাউড অডিও, দ্রুতগতির হাইব্রিড টার্বো, অথবা অফলাইন মোড বাছাই করুন।",
        "lang_gesture_title" to "ভাষা ও কুইক সুইচ",
        "default_stt_lang" to "ডিফল্ট ডিক্টেশন ভাষা:",
        "double_tap_toggle" to "বাটনে ডাবল-ট্যাপ করে ভাষা পরিবর্তন",
        "double_tap_toggle_desc" to "বাটনে পর পর দুইবার ট্যাপ করে বাংলা (বাং) ও ইংরেজির (EN) মাঝে দ্রুত পরিবর্তন করুন।",
        "live_preview" to "লাইভ প্রিভিউ টেক্সট",
        "live_preview_desc" to "কথা বলার সময় শব্দ দেখতে পারবেন। Xiaomi ফোনে কম ব্যাটারি ও দ্রুতগতির জন্য বন্ধ রাখা ভালো।",
        "offline_voice_setup_btn" to "গুগল ভয়েস টাইপিং সেটিংস খুলুন",
        "offline_voice_setup_desc" to "অফলাইনে কথা থেকে লেখার জন্য বাংলা (বাংলাদেশ) ও ইংরেজি অফলাইন প্যাক ডাউনলোড করুন।",
        "provider_title" to "ক্লাউড পলিশ সার্ভিস (হাইব্রিড মোড)",
        "provider_desc" to "কোন এআই সার্ভিস দ্রুত তোতলামি দূর করবে ও দাঁড়ি-কমা বসাবে তা নির্বাচন করুন।",
        "model_downloader_title" to "অন-ডিভাইস Gemma 3 (1B) মডেল",
        "model_not_downloaded" to "Gemma 3 মডেল ডাউনলোড করা হয়নি (~৭৫০ এমবি)।",
        "model_downloading" to "Gemma 3 মডেল ডাউনলোড হচ্ছে...",
        "model_downloaded" to "Gemma 3 মডেল প্রস্তুত (সম্পূর্ণ অফলাইনে এআই দিয়ে লেখা ফরম্যাট হবে)।",
        "download_model_btn" to "Gemma 3 ডাউনলোড করুন (~৭৫০ এমবি)",
        "cancel_download_btn" to "বাতিল করুন",
        "delete_model_btn" to "মডেল মুছে ফেলুন",
        "hf_token_hint" to "Hugging Face টোকেন (ঐচ্ছিক)",
        "help_btn" to "সেটাপ নির্দেশিকা"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestXiaomiAutostart: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { FlowBanglaApp.instance.preferencesManager }
    val historyManager = remember { FlowBanglaApp.instance.historyManager }
    val modelDownloadManager = remember { ModelDownloadManager(context, prefs) }
    val downloadState by modelDownloadManager.downloadState.collectAsState()
    val scope = rememberCoroutineScope()

    var appLanguage by remember { mutableStateOf(prefs.appLanguage) }
    val isBn = appLanguage == "bn"
    fun str(key: String): String = L10n.t(key, isBn)

    var isServiceRunning by remember { mutableStateOf(FloatingBubbleService.isRunning()) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // Engine & Provider state
    var selectedEngineMode by remember { mutableStateOf(prefs.engineMode) }
    var selectedProvider by remember { mutableStateOf(prefs.cloudPolishProvider) }
    var selectedSttLanguage by remember { mutableStateOf(prefs.sttLanguage) }
    var isDoubleTapEnabled by remember { mutableStateOf(prefs.isDoubleTapLanguageToggleEnabled) }
    var isLivePreviewEnabled by remember { mutableStateOf(prefs.isLivePreviewEnabled) }

    // API Keys state
    var geminiKeyText by remember { mutableStateOf(prefs.geminiApiKey) }
    var groqKeyText by remember { mutableStateOf(prefs.groqApiKey) }
    var cerebrasKeyText by remember { mutableStateOf(prefs.cerebrasApiKey) }
    var openRouterKeyText by remember { mutableStateOf(prefs.openRouterApiKey) }
    var groqModelText by remember { mutableStateOf(prefs.groqModelName) }
    var hfTokenText by remember { mutableStateOf("") }

    var showApiKey by remember { mutableStateOf(false) }
    var isTestingKey by remember { mutableStateOf(false) }
    var testKeyMessage by remember { mutableStateOf<String?>(null) }
    var connectivityReport by remember { mutableStateOf<ConnectivityLabReport?>(null) }

    // Floating bubble visuals
    var interactionMode by remember { mutableStateOf(prefs.interactionMode) }
    var isSmartPolish by remember { mutableStateOf(prefs.smartPolishEnabled) }
    var isHaptic by remember { mutableStateOf(prefs.hapticFeedbackEnabled) }
    var bubbleSize by remember { mutableFloatStateOf(prefs.bubbleSizeDp.toFloat()) }
    var bubbleOpacity by remember { mutableFloatStateOf(prefs.bubbleOpacity) }
    var showOnlyWithKeyboard by remember { mutableStateOf(prefs.showOnlyWithKeyboard) }
    var isMicDismissed by remember { mutableStateOf(FloatingBubbleService.instance?.isManuallyDismissed == true) }

    var recentHistory by remember { mutableStateOf(historyManager.getRecentRecords()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    val hasAccessibility = TextInjectorAccessibilityService.isEnabled(context)
    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(AccentCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                str("app_title"),
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                str("app_subtitle"),
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    // Help Dialog Button
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Help", tint = AccentCyan)
                    }

                    // Language Switcher Pill
                    FilledTonalButton(
                        onClick = {
                            val nextLang = if (isBn) "en" else "bn"
                            appLanguage = nextLang
                            prefs.appLanguage = nextLang
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SurfaceCard,
                            contentColor = AccentCyan
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isBn) "English" else "বাংলা", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(str("nav_home")) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        recentHistory = historyManager.getRecentRecords()
                        selectedTab = 1
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("${str("nav_history")} (${recentHistory.size})") }
                )
            }
        },
        containerColor = BackgroundDark
    ) { padding ->
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // Master Toggle Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isServiceRunning) PrimaryBlueVariant else SurfaceDark
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(20.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (isServiceRunning) str("master_on") else str("master_off"),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (isServiceRunning) str("master_desc_on") else str("master_desc_off"),
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = isServiceRunning,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (!prefs.isConfigured) {
                                            Toast.makeText(context, str("save_key_first"), Toast.LENGTH_SHORT).show()
                                            return@Switch
                                        }
                                        if (!hasOverlay) {
                                            onRequestOverlayPermission()
                                            return@Switch
                                        }
                                        val intent = Intent(context, FloatingBubbleService::class.java).apply {
                                            action = FloatingBubbleService.ACTION_START_SERVICE
                                        }
                                        ContextCompat.startForegroundService(context, intent)
                                        isServiceRunning = true
                                    } else {
                                        val intent = Intent(context, FloatingBubbleService::class.java).apply {
                                            action = FloatingBubbleService.ACTION_STOP_SERVICE
                                        }
                                        context.stopService(intent)
                                        isServiceRunning = false
                                    }
                                },
                                enabled = prefs.isConfigured && hasOverlay && hasAccessibility && hasMic
                            )
                        }
                    }
                }

                // Xiaomi / Permissions Checklist Card
                item {
                    val allGranted = hasOverlay && hasAccessibility && hasMic
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (allGranted) SurfaceDark else Color(0xFF2D1515)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (allGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (allGranted) SuccessGreen else RecordingRed
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (allGranted) str("perm_all_good") else str("perm_needed"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                            }

                            if (!allGranted) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    str("perm_desc"),
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                PermissionRowItem(
                                    title = str("perm_overlay"),
                                    isGranted = hasOverlay,
                                    buttonLabel = str("enable"),
                                    onGrant = onRequestOverlayPermission
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                PermissionRowItem(
                                    title = str("perm_accessibility"),
                                    isGranted = hasAccessibility,
                                    buttonLabel = str("enable"),
                                    onGrant = onRequestAccessibilityPermission
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                PermissionRowItem(
                                    title = str("perm_autostart"),
                                    isGranted = true,
                                    buttonLabel = str("enable"),
                                    onGrant = onRequestXiaomiAutostart
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                PermissionRowItem(
                                    title = str("perm_battery"),
                                    isGranted = true,
                                    buttonLabel = str("enable"),
                                    onGrant = onRequestBatteryExemption
                                )
                            }
                        }
                    }
                }

                // 1. Engine Mode Selector Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = AccentCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        str("engine_mode_title"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        str("engine_mode_desc"),
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            EngineMode.entries.forEach { mode ->
                                val isSelected = selectedEngineMode == mode
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) SurfaceCard else BackgroundDark
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedEngineMode = mode
                                            prefs.engineMode = mode
                                            notifyServiceSettingsChanged(context)
                                        }
                                        .then(
                                            if (isSelected) Modifier.border(1.5.dp, AccentCyan, RoundedCornerShape(12.dp))
                                            else Modifier
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                selectedEngineMode = mode
                                                prefs.engineMode = mode
                                                notifyServiceSettingsChanged(context)
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                if (isBn) mode.titleBn else mode.titleEn,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) AccentCyan else TextPrimary
                                            )
                                            Text(
                                                if (isBn) mode.descBn else mode.descEn,
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. STT Language & Gestures Card (when Hybrid or Offline mode is selected)
                if (selectedEngineMode != EngineMode.CLOUD_AUDIO) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Translate, contentDescription = null, tint = AccentCyan)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        str("lang_gesture_title"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = TextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))

                                // Language Selector
                                Text(str("default_stt_lang"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SttLanguage.entries.forEach { lang ->
                                        FilterChip(
                                            selected = selectedSttLanguage == lang,
                                            onClick = {
                                                selectedSttLanguage = lang
                                                prefs.sttLanguage = lang
                                                notifyServiceSettingsChanged(context)
                                            },
                                            label = { Text(if (isBn) lang.labelBn else lang.labelEn) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                                // Double-tap language toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(str("double_tap_toggle"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        Text(str("double_tap_toggle_desc"), fontSize = 12.sp, color = TextSecondary)
                                    }
                                    Switch(
                                        checked = isDoubleTapEnabled,
                                        onCheckedChange = {
                                            isDoubleTapEnabled = it
                                            prefs.isDoubleTapLanguageToggleEnabled = it
                                            notifyServiceSettingsChanged(context)
                                        }
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                                // Live preview switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(str("live_preview"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        Text(str("live_preview_desc"), fontSize = 12.sp, color = TextSecondary)
                                    }
                                    Switch(
                                        checked = isLivePreviewEnabled,
                                        onCheckedChange = {
                                            isLivePreviewEnabled = it
                                            prefs.isLivePreviewEnabled = it
                                        }
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                                // Google Voice Typing quick-action button
                                Button(
                                    onClick = { openVoiceInputSettings(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(str("offline_voice_setup_btn"), fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    str("offline_voice_setup_desc"),
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // 3. Dynamic Backend / Provider Configuration Card
                when (selectedEngineMode) {
                    EngineMode.CLOUD_AUDIO -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = AccentCyan)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            str("api_title"),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = TextPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        str("api_desc"),
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = geminiKeyText,
                                        onValueChange = {
                                            geminiKeyText = it
                                            prefs.geminiApiKey = it
                                        },
                                        label = { Text(str("api_hint")) },
                                        singleLine = true,
                                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                                Icon(
                                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = null
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            focusedBorderColor = AccentCyan,
                                            unfocusedBorderColor = SurfaceCard
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                                if (!clip.isNullOrBlank()) {
                                                    geminiKeyText = clip.trim()
                                                    prefs.geminiApiKey = geminiKeyText
                                                    Toast.makeText(context, if (isBn) "API Key সেভ হয়েছে" else "API Key saved", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(str("paste"))
                                        }

                                        Button(
                                            onClick = {
                                                if (geminiKeyText.isBlank()) return@Button
                                                isTestingKey = true
                                                testKeyMessage = null
                                                connectivityReport = null
                                                scope.launch {
                                                    val report = ConnectivityLabRunner.runThreeStageTest(
                                                        context = context,
                                                        http = OkHttpClient(),
                                                        rawApiKey = geminiKeyText,
                                                        latestRecordedFile = null
                                                    )
                                                    isTestingKey = false
                                                    connectivityReport = report
                                                }
                                            },
                                            enabled = !isTestingKey && geminiKeyText.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                        ) {
                                            if (isTestingKey) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Testing...")
                                            } else {
                                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Connectivity Lab (S1-S3)")
                                            }
                                        }
                                    }

                                    connectivityReport?.let { report ->
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(SurfaceDark, RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                "S1 Key & Models: ${report.s1Status}",
                                                fontSize = 12.sp,
                                                color = if (report.s1Status.startsWith("PASS")) SuccessGreen else RecordingRed
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "S2 Files API Upload: ${report.s2Status}",
                                                fontSize = 12.sp,
                                                color = if (report.s2Status.startsWith("PASS")) SuccessGreen else RecordingRed
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "S3 Interactions Call: ${report.s3Status}",
                                                fontSize = 12.sp,
                                                color = if (report.s3Status.startsWith("PASS")) SuccessGreen else RecordingRed
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("FlowDiagnostics", report.redactedSummary))
                                                    Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Copy Redacted Diagnostics", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    EngineMode.HYBRID_CLOUD_POLISH -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CloudQueue, contentDescription = null, tint = AccentCyan)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                str("provider_title"),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                color = TextPrimary
                                            )
                                            Text(
                                                str("provider_desc"),
                                                fontSize = 12.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Provider Selection Tabs
                                    CloudPolishProvider.entries.forEach { provider ->
                                        val isSelected = selectedProvider == provider
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) SurfaceCard else BackgroundDark
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                                .clickable {
                                                    selectedProvider = provider
                                                    prefs.cloudPolishProvider = provider
                                                }
                                                .then(
                                                    if (isSelected) Modifier.border(1.2.dp, AccentCyan, RoundedCornerShape(10.dp))
                                                    else Modifier
                                                )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = {
                                                        selectedProvider = provider
                                                        prefs.cloudPolishProvider = provider
                                                    },
                                                    colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column {
                                                    Text(provider.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                                    Text(provider.freeQuotaSummary, fontSize = 11.sp, color = TextSecondary)
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Dynamic Key Input for the Selected Provider
                                    when (selectedProvider) {
                                        CloudPolishProvider.GEMINI -> {
                                            ApiKeyInputField(
                                                title = "Gemini API Key",
                                                value = geminiKeyText,
                                                onValueChange = {
                                                    geminiKeyText = it
                                                    prefs.geminiApiKey = it
                                                },
                                                hint = "Enter Gemini API Key (Reuses Studio Key)",
                                                showKey = showApiKey,
                                                onToggleShowKey = { showApiKey = !showApiKey },
                                                context = context
                                            )
                                        }
                                        CloudPolishProvider.GROQ -> {
                                            ApiKeyInputField(
                                                title = "Groq API Key (gsk_...)",
                                                value = groqKeyText,
                                                onValueChange = {
                                                    groqKeyText = it
                                                    prefs.groqApiKey = it
                                                },
                                                hint = "gsk_...",
                                                showKey = showApiKey,
                                                onToggleShowKey = { showApiKey = !showApiKey },
                                                context = context
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = groqModelText,
                                                onValueChange = {
                                                    groqModelText = it
                                                    prefs.groqModelName = it
                                                },
                                                label = { Text("Groq Model ID") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedBorderColor = AccentCyan,
                                                    unfocusedBorderColor = SurfaceCard
                                                )
                                            )
                                        }
                                        CloudPolishProvider.CEREBRAS -> {
                                            ApiKeyInputField(
                                                title = "Cerebras API Key (csk-...)",
                                                value = cerebrasKeyText,
                                                onValueChange = {
                                                    cerebrasKeyText = it
                                                    prefs.cerebrasApiKey = it
                                                },
                                                hint = "csk-...",
                                                showKey = showApiKey,
                                                onToggleShowKey = { showApiKey = !showApiKey },
                                                context = context
                                            )
                                        }
                                        CloudPolishProvider.OPENROUTER -> {
                                            ApiKeyInputField(
                                                title = "OpenRouter API Key (sk-or-...)",
                                                value = openRouterKeyText,
                                                onValueChange = {
                                                    openRouterKeyText = it
                                                    prefs.openRouterApiKey = it
                                                },
                                                hint = "sk-or-v1-...",
                                                showKey = showApiKey,
                                                onToggleShowKey = { showApiKey = !showApiKey },
                                                context = context
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    EngineMode.OFFLINE_LIGHTWEIGHT -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, tint = SuccessGreen)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            if (isBn) "অফলাইন লাইটওয়েট প্রস্তুত" else "Offline Lightweight Ready",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = TextPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        if (isBn) "কোনো ক্লাউড বা ইন্টারনেট ছাড়াই সম্পূর্ণ ফ্রি ও ০ এমবি অতিরিক্ত র‍্যামে কাজ করে।"
                                        else "100% offline with zero cloud API keys, zero data usage, and <5ms response latency.",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    EngineMode.OFFLINE_LOCAL_LLM -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Memory, contentDescription = null, tint = AccentCyan)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            str("model_downloader_title"),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = TextPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    when (val state = downloadState) {
                                        is ModelDownloadState.NotDownloaded -> {
                                            Text(str("model_not_downloaded"), fontSize = 13.sp, color = TextSecondary)
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = hfTokenText,
                                                onValueChange = { hfTokenText = it },
                                                label = { Text(str("hf_token_hint")) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = TextPrimary,
                                                    unfocusedTextColor = TextPrimary,
                                                    focusedBorderColor = AccentCyan,
                                                    unfocusedBorderColor = SurfaceCard
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        modelDownloadManager.downloadModel(hfToken = hfTokenText)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(str("download_model_btn"))
                                            }
                                        }

                                        is ModelDownloadState.Downloading -> {
                                            Text(str("model_downloading"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AccentCyan)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { state.progressPercent / 100f },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("${state.downloadedMb} MB / ${state.totalMb} MB (${state.progressPercent}%)", fontSize = 12.sp, color = TextSecondary)
                                                TextButton(onClick = { modelDownloadManager.cancelDownload() }) {
                                                    Text(str("cancel_download_btn"), color = RecordingRed)
                                                }
                                            }
                                        }

                                        is ModelDownloadState.Downloaded -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(str("model_downloaded"), fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Size: ${state.sizeMb} MB | Auto-unloads after 2 mins idle", fontSize = 12.sp, color = TextSecondary)
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedButton(
                                                onClick = { modelDownloadManager.deleteDownloadedModel() },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RecordingRed)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(str("delete_model_btn"))
                                            }
                                        }

                                        is ModelDownloadState.Error -> {
                                            Text("⚠️ ${state.message}", color = RecordingRed, fontSize = 13.sp)
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        modelDownloadManager.downloadModel(hfToken = hfTokenText)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                            ) {
                                                Text("Retry Download")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Voice & Floating Button Settings Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = AccentCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(str("settings_title"), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Smart Polish Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(str("smart_polish"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(str("smart_polish_desc"), fontSize = 12.sp, color = TextSecondary)
                                }
                                Switch(
                                    checked = isSmartPolish,
                                    onCheckedChange = {
                                        isSmartPolish = it
                                        prefs.smartPolishEnabled = it
                                    }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                            // Interaction Mode: Tap vs Push
                            Text(str("touch_mode"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = interactionMode == InteractionMode.TAP_TO_TALK,
                                    onClick = {
                                        interactionMode = InteractionMode.TAP_TO_TALK
                                        prefs.interactionMode = InteractionMode.TAP_TO_TALK
                                    },
                                    label = { Text(str("mode_tap")) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = interactionMode == InteractionMode.PUSH_TO_TALK,
                                    onClick = {
                                        interactionMode = InteractionMode.PUSH_TO_TALK
                                        prefs.interactionMode = InteractionMode.PUSH_TO_TALK
                                    },
                                    label = { Text(str("mode_push")) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                            // Haptic Feedback Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(str("haptic"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(str("haptic_desc"), fontSize = 12.sp, color = TextSecondary)
                                }
                                Switch(
                                    checked = isHaptic,
                                    onCheckedChange = {
                                        isHaptic = it
                                        prefs.hapticFeedbackEnabled = it
                                    }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                            // Wispr Flow Keyboard-Only Auto Activation Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(str("only_keyboard"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                    Text(str("only_keyboard_desc"), fontSize = 12.sp, color = TextSecondary)
                                }
                                Switch(
                                    checked = showOnlyWithKeyboard,
                                    onCheckedChange = {
                                        showOnlyWithKeyboard = it
                                        prefs.showOnlyWithKeyboard = it
                                        FloatingBubbleService.instance?.applyVisibilityRule()
                                    }
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                            // Floating Button Size Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(str("bubble_size"), color = TextPrimary)
                                    Text("${bubbleSize.toInt()} dp", color = AccentCyan, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = bubbleSize,
                                    onValueChange = {
                                        bubbleSize = it
                                        prefs.bubbleSizeDp = it.toInt()
                                        FloatingBubbleService.instance?.updateDimensionsAndOpacity()
                                    },
                                    valueRange = 48f..84f,
                                    steps = 6
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                            // Floating Button Opacity Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(str("bubble_opacity"), color = TextPrimary)
                                    Text("${(bubbleOpacity * 100).toInt()}%", color = AccentCyan, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = bubbleOpacity,
                                    onValueChange = {
                                        bubbleOpacity = it
                                        prefs.bubbleOpacity = it
                                        FloatingBubbleService.instance?.updateDimensionsAndOpacity()
                                    },
                                    valueRange = 0.30f..1.0f,
                                    steps = 7
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCard)

                            // Quick Hide/Show button matching Messenger drag-to-dismiss behavior
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(str("mic_visibility"), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (isMicDismissed) str("mic_hidden_desc") else str("mic_visible_desc"),
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        val service = FloatingBubbleService.instance
                                        if (service != null) {
                                            service.isManuallyDismissed = !service.isManuallyDismissed
                                            service.applyVisibilityRule()
                                            service.updateNotification()
                                            isMicDismissed = service.isManuallyDismissed
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isMicDismissed) AccentCyan else SurfaceCard
                                    )
                                ) {
                                    Text(
                                        if (isMicDismissed) str("show_mic") else str("hide_mic"),
                                        color = if (isMicDismissed) Color.Black else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        } else {
            // History Tab
            HistoryScreen(
                history = recentHistory,
                isBn = isBn,
                onCopy = { text ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Flow", text))
                    Toast.makeText(context, str("copied"), Toast.LENGTH_SHORT).show()
                },
                onClear = {
                    historyManager.clearHistory()
                    recentHistory = emptyList()
                }
            )
        }
    }

    // Setup & API Keys Guide Dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(str("help_btn"), fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (isBn) "১. অফলাইন বাংলা ও ইংরেজি ভয়েস প্যাক:"
                        else "1. Offline Voice Typing in Gboard:",
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Text(
                        if (isBn) "ফোনের Settings > Additional Settings > Languages & input > Google Voice Typing > Offline speech recognition-এ গিয়ে বাংলা (বাংলাদেশ) এবং English (US) ডাউনলোড করুন।"
                        else "Open Settings > Additional Settings > Languages & input > Google Voice Typing > Offline speech recognition, then download বাংলা (বাংলাদেশ) & English (US).",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    HorizontalDivider(color = SurfaceCard)

                    Text(
                        if (isBn) "২. ফ্রি ক্লাউড পলিশ API Keys:"
                        else "2. Free Cloud Polish API Keys:",
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Text(
                        "• Gemini: aistudio.google.com (1,500 RPD free)\n" +
                                "• Groq: console.groq.com (30 RPM, 1,000-14,400 RPD free)\n" +
                                "• Cerebras: inference-docs.cerebras.ai (instant ~2,000 tok/sec free)\n" +
                                "• OpenRouter: openrouter.ai/keys (free community router)",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    HorizontalDivider(color = SurfaceCard)

                    Text(
                        if (isBn) "৩. অফলাইন AI মডেল (Gemma 3 1B):"
                        else "3. On-Device Gemma 3 Model:",
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Text(
                        if (isBn) "এটি সম্পূর্ণ অফলাইনে Gemma 3 ব্যবহার করে দাড়ি-কমা ঠিক করে। মডেল ব্যবহারের ২ মিনিট পর স্বয়ংক্রিয়ভাবে র‍্যাম খালি হয়ে যায়।"
                        else "Loads locally via LiteRT-LM. Automatically frees RAM after 2 minutes of inactivity to preserve battery.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("OK", color = AccentCyan)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun ApiKeyInputField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    context: Context
) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, fontSize = 12.sp) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleShowKey) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = SurfaceCard
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrBlank()) {
                        onValueChange(clip.trim())
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Paste", fontSize = 12.sp)
            }
        }
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

private fun openVoiceInputSettings(context: Context) {
    val intents = listOf(
        Intent("com.google.android.gms.speech.agenda"),
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent("android.speech.action.VOICE_SEARCH_SETTINGS"),
        Intent(Settings.ACTION_LOCALE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in intents) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}

@Composable
fun PermissionRowItem(
    title: String,
    isGranted: Boolean,
    buttonLabel: String,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            color = if (isGranted) TextSecondary else TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (isGranted) {
            Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
        } else {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(buttonLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun HistoryScreen(
    history: List<DictationRecord>,
    isBn: Boolean,
    onCopy: (String) -> Unit,
    onClear: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                L10n.t("history_title", isBn),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text(L10n.t("clear_history", isBn), color = RecordingRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MicNone,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(L10n.t("no_history", isBn), color = TextSecondary)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    dateFormat.format(Date(item.timestamp)),
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (item.provenance.isNotBlank()) {
                                        Surface(
                                            color = SurfaceCard,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                item.provenance,
                                                fontSize = 10.sp,
                                                color = AccentCyan,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (item.durationSeconds > 0) {
                                        Text("${item.durationSeconds} ${L10n.t("seconds", isBn)}", fontSize = 11.sp, color = AccentCyan)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                item.text,
                                fontSize = 15.sp,
                                color = TextPrimary,
                                lineHeight = 22.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { onCopy(item.text) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(L10n.t("copy", isBn), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


