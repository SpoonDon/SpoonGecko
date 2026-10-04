package com.spoongecko.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization + OEM-autostart helpers.
 *
 * The single biggest cause of "my GeckoView browser keeps dying" on
 * consumer Android devices is OEM power management. Even a perfect
 * foreground service gets reaped on MIUI/ColorOS/FuntouchOS/One UI/EMUI
 * unless the user whitelists the app in the OEM's own autostart list.
 * We can't do it for them (only Settings can), but we can deep-link.
 */
object OemHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Try to open the OEM's autostart / protected-apps screen. Returns the
     * label the caller should show the user, or null if we had to fall back
     * to generic App Info.
     */
    fun openOemAutostartSettings(context: Context): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidates: List<Pair<String, ComponentName>> = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
                "MIUI Autostart" to ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> listOf(
                "ColorOS Startup Manager" to ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                "ColorOS Startup Manager (alt)" to ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                "Funtouch Autostart" to ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                "iQOO Autostart" to ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
            manufacturer.contains("samsung") -> listOf(
                "Samsung Device Care" to ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                "EMUI Protected Apps" to ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                "EMUI Protected Apps (alt)" to ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            else -> emptyList()
        }

        for ((label, component) in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return label
        }

        // Fallback: open the app's system info page so the user can dig in.
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }
        return "App Info (OEM autostart not found automatically)"
    }
}
