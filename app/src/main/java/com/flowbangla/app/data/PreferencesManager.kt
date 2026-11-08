package com.flowbangla.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.flowbangla.app.model.CloudPolishProvider
import com.flowbangla.app.model.EngineMode
import com.flowbangla.app.model.InteractionMode
import com.flowbangla.app.model.SttLanguage

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "flowbangla_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("PreferencesManager", "Hardware keystore fallback: ${e.message}")
        context.getSharedPreferences("flowbangla_plain_prefs", Context.MODE_PRIVATE)
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    // Alias for geminiApiKey for compatibility
    var apiKey: String
        get() = geminiApiKey
        set(value) { geminiApiKey = value }

    var personalVocabulary: String
        get() = prefs.getString(KEY_PERSONAL_VOCABULARY, "")?.trim() ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONAL_VOCABULARY, value.trim()).apply()

    fun getPersonalVocabularyList(): List<String> {
        val raw = personalVocabulary
        if (raw.isBlank()) return emptyList()
        return raw.split(",", "\n", ";").map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(100)
    }

    var interactionMode: InteractionMode
        get() {
            val name = prefs.getString(KEY_INTERACTION_MODE, InteractionMode.TAP_TO_TALK.name)
            return try {
                InteractionMode.valueOf(name ?: InteractionMode.TAP_TO_TALK.name)
            } catch (e: Exception) {
                InteractionMode.TAP_TO_TALK
            }
        }
        set(value) = prefs.edit().putString(KEY_INTERACTION_MODE, value.name).apply()

    var smartPolishEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_POLISH, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_POLISH, value).apply()

    var hapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var bubbleSizeDp: Int
        get() = prefs.getInt(KEY_BUBBLE_SIZE, 64)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_SIZE, value).apply()

    var bubblePosX: Int
        get() = prefs.getInt(KEY_POS_X, -1)
        set(value) = prefs.edit().putInt(KEY_POS_X, value).apply()

    var bubblePosY: Int
        get() = prefs.getInt(KEY_POS_Y, -1)
        set(value) = prefs.edit().putInt(KEY_POS_Y, value).apply()

    var bubbleOpacity: Float
        get() = prefs.getFloat(KEY_BUBBLE_OPACITY, 0.90f)
        set(value) = prefs.edit().putFloat(KEY_BUBBLE_OPACITY, value).apply()

    var isMasterSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_SWITCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER_SWITCH_ENABLED, value).apply()

    var showOnlyWhenTyping: Boolean
        get() = prefs.getBoolean(KEY_SHOW_WITH_KEYBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_WITH_KEYBOARD, value).apply()

    var showOnlyWithKeyboard: Boolean
        get() = showOnlyWhenTyping
        set(value) { showOnlyWhenTyping = value }

    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    // Flow v2.0 Engine and Provider Preferences
    var engineMode: EngineMode
        get() = EngineMode.fromId(prefs.getString(KEY_ENGINE_MODE, EngineMode.CLOUD_AUDIO.id))
        set(value) = prefs.edit().putString(KEY_ENGINE_MODE, value.id).apply()

    var cloudPolishProvider: CloudPolishProvider
        get() = CloudPolishProvider.fromId(prefs.getString(KEY_CLOUD_POLISH_PROVIDER, CloudPolishProvider.GEMINI.id))
        set(value) = prefs.edit().putString(KEY_CLOUD_POLISH_PROVIDER, value.id).apply()

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ_API_KEY, value.trim()).apply()

    var cerebrasApiKey: String
        get() = prefs.getString(KEY_CEREBRAS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CEREBRAS_API_KEY, value.trim()).apply()

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENROUTER_API_KEY, value.trim()).apply()

    var groqModelName: String
        get() = prefs.getString(KEY_GROQ_MODEL_NAME, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
        set(value) = prefs.edit().putString(KEY_GROQ_MODEL_NAME, value.trim()).apply()

    var sttLanguage: SttLanguage
        get() = SttLanguage.fromTag(prefs.getString(KEY_STT_LANGUAGE, SttLanguage.BENGALI.localeTag))
        set(value) = prefs.edit().putString(KEY_STT_LANGUAGE, value.localeTag).apply()

    var isDoubleTapLanguageToggleEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_LANG_TOGGLE, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_TAP_LANG_TOGGLE, value).apply()

    // Default FALSE so mid-range Xiaomi phones only inject the final polished text with zero CPU frame jitter
    var isLivePreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LIVE_PREVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LIVE_PREVIEW, value).apply()

    var downloadedLocalModelPath: String?
        get() = prefs.getString(KEY_DOWNLOADED_LOCAL_MODEL_PATH, null)
        set(value) = prefs.edit().putString(KEY_DOWNLOADED_LOCAL_MODEL_PATH, value).apply()

    var autoCleanupLevel: com.flowbangla.app.format.AutoCleanupLevel
        get() {
            val name = prefs.getString(KEY_AUTO_CLEANUP_LEVEL, com.flowbangla.app.format.AutoCleanupLevel.MEDIUM.name)
            return try {
                com.flowbangla.app.format.AutoCleanupLevel.valueOf(name ?: com.flowbangla.app.format.AutoCleanupLevel.MEDIUM.name)
            } catch (_: Exception) {
                com.flowbangla.app.format.AutoCleanupLevel.MEDIUM
            }
        }
        set(value) = prefs.edit().putString(KEY_AUTO_CLEANUP_LEVEL, value.name).apply()

    var appearanceTheme: String
        get() = prefs.getString(KEY_APPEARANCE_THEME, "dark") ?: "dark"
        set(value) = prefs.edit().putString(KEY_APPEARANCE_THEME, value).apply()

    var accentColorKey: String
        get() = prefs.getString(KEY_ACCENT_COLOR, "ivory") ?: "ivory"
        set(value) = prefs.edit().putString(KEY_ACCENT_COLOR, value).apply()

    var casualMessagingDropPeriod: Boolean
        get() = prefs.getBoolean(KEY_CASUAL_MESSAGING_DROP_PERIOD, true)
        set(value) = prefs.edit().putBoolean(KEY_CASUAL_MESSAGING_DROP_PERIOD, value).apply()

    val isConfigured: Boolean
        get() = when (engineMode) {
            EngineMode.CLOUD_AUDIO -> geminiApiKey.isNotBlank()
            EngineMode.HYBRID_CLOUD_POLISH -> when (cloudPolishProvider) {
                CloudPolishProvider.GEMINI -> geminiApiKey.isNotBlank()
                CloudPolishProvider.GROQ -> groqApiKey.isNotBlank()
                CloudPolishProvider.CEREBRAS -> cerebrasApiKey.isNotBlank()
                CloudPolishProvider.OPENROUTER -> openRouterApiKey.isNotBlank()
            }
            EngineMode.OFFLINE_LIGHTWEIGHT -> true
            EngineMode.OFFLINE_LOCAL_LLM -> !downloadedLocalModelPath.isNullOrBlank()
        }

    companion object {
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_MASTER_SWITCH_ENABLED = "master_switch_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_INTERACTION_MODE = "interaction_mode"
        private const val KEY_SMART_POLISH = "smart_polish"
        private const val KEY_HAPTIC = "haptic_feedback"
        private const val KEY_BUBBLE_SIZE = "bubble_size_dp"
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        private const val KEY_SHOW_WITH_KEYBOARD = "show_with_keyboard"
        private const val KEY_POS_X = "bubble_pos_x"
        private const val KEY_POS_Y = "bubble_pos_y"

        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_CLOUD_POLISH_PROVIDER = "cloud_polish_provider"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_CEREBRAS_API_KEY = "cerebras_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_GROQ_MODEL_NAME = "groq_model_name"
        private const val KEY_STT_LANGUAGE = "stt_language"
        private const val KEY_DOUBLE_TAP_LANG_TOGGLE = "double_tap_lang_toggle"
        private const val KEY_ENABLE_LIVE_PREVIEW = "enable_live_preview"
        private const val KEY_DOWNLOADED_LOCAL_MODEL_PATH = "downloaded_local_model_path"
        private const val KEY_PERSONAL_VOCABULARY = "personal_vocabulary"
        private const val KEY_AUTO_CLEANUP_LEVEL = "auto_cleanup_level"
        private const val KEY_CASUAL_MESSAGING_DROP_PERIOD = "casual_messaging_drop_period"
        private const val KEY_APPEARANCE_THEME = "appearance_theme"
        private const val KEY_ACCENT_COLOR = "accent_color"
    }
}
