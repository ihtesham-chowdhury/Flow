package com.flowbangla.app.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.flowbangla.app.service.TextInjectorAccessibilityService

data class FlowPermissionState(
    val hasOverlayPermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val hasRecordAudioPermission: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false
) {
    val isRestrictedSettingsPossible: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val allCriticalGranted: Boolean
        get() = hasOverlayPermission && hasAccessibilityPermission && hasRecordAudioPermission
}

object PermissionHelper {

    fun checkAllPermissions(context: Context): FlowPermissionState {
        return FlowPermissionState(
            hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            },
            hasAccessibilityPermission = isAccessibilityServiceEnabled(context),
            hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        )
    }

    /**
     * Checks three independent sources so MIUI/HyperOS and Android 13-15
     * immediately report true the moment the user toggles the switch ON.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // 1. Live bound instance check
        if (TextInjectorAccessibilityService.instance != null) {
            return true
        }

        val expectedComponent = ComponentName(context, TextInjectorAccessibilityService::class.java)

        // 2. AccessibilityManager active service list check
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null && am.isEnabled) {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val foundInManager = enabledServices.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.packageName == context.packageName &&
                    serviceInfo.name == TextInjectorAccessibilityService::class.java.name
            }
            if (foundInManager) return true
        }

        // 3. Settings.Secure colon-separated string check (handles both full and shorthand class names)
        val enabledSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        if (enabledSetting.isNotEmpty()) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledSetting)
            while (splitter.hasNext()) {
                val componentNameString = splitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponent) {
                    return true
                }
            }
        }

        return false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.contains("samsung", ignoreCase = true)
    }

    /**
     * Intent targeting Samsung Device Care / Battery management to prevent One UI
     * from putting Flow into 'Sleeping Apps' or 'Deep Sleeping Apps'.
     */
    fun createSamsungDeviceCareIntent(): android.content.Intent {
        val intent = android.content.Intent()
        return try {
            intent.component = ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            intent
        } catch (_: Exception) {
            android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }
}
