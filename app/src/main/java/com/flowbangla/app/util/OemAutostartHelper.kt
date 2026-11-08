package com.flowbangla.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object OemAutostartHelper {

    private val OEM_AUTOSTART_COMPONENTS = listOf(
        // Xiaomi / Redmi / POCO (MIUI & HyperOS)
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        // Samsung (One UI Device Care)
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        // OPPO / Realme / OnePlus (ColorOS / OxygenOS)
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        // Vivo / iQOO (FuntouchOS / OriginOS)
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // Huawei / Honor
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // Transsion (Tecno / Infinix / itel — very common in Bangladesh!)
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
    )

    /**
     * 1. Requests standard Android ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    /**
     * 2. Opens the exact OEM Autostart / App Launch screen for Xiaomi, Samsung, OPPO, Vivo, Realme, OnePlus, or Tecno/Infinix.
     */
    fun openOemAutostartSettings(context: Context): Boolean {
        for (component in OEM_AUTOSTART_COMPONENTS) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {}
        }
        return openAppDetailsSettings(context)
    }

    private fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getOemInstructions(isBangla: Boolean): String {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            "xiaomi" in brand || "redmi" in brand || "poco" in brand ->
                if (isBangla) "১. Autostart চালু করুন\n২. Battery Saver -> No restrictions দিন\n৩. Recent Apps-এ Flow লক করুন"
                else "1. Enable Autostart\n2. Set Battery Saver to 'No restrictions'\n3. Lock Flow in Recent Apps"
            "samsung" in brand ->
                if (isBangla) "১. Battery -> Unrestricted সিলেক্ট করুন\n২. Never sleeping apps-এ Flow যোগ করুন"
                else "1. Select Battery -> Unrestricted\n2. Add Flow to 'Never sleeping apps'"
            "oppo" in brand || "realme" in brand || "oneplus" in brand ->
                if (isBangla) "১. Allow Auto-Launch চালু করুন\n২. Allow Background Activity চালু করুন"
                else "1. Enable Allow Auto-Launch\n2. Enable Allow Background Activity"
            "vivo" in brand || "iqoo" in brand ->
                if (isBangla) "১. Autostart চালু করুন\n২. High background power consumption চালু করুন"
                else "1. Enable Autostart\n2. Allow High background power consumption"
            else ->
                if (isBangla) "Battery -> Unrestricted / Don't Optimize সিলেক্ট করুন"
                else "Select Battery -> Unrestricted / Don't Optimize"
        }
    }
}
