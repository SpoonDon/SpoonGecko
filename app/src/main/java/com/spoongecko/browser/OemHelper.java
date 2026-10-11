package com.spoongecko.browser;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Helpers for the settings screens that actually decide whether an OEM ROM
 * kills the app. Most of these are not part of the public Android SDK, so every
 * call is wrapped and falls back to the generic app-details screen.
 */
public final class OemHelper {

    private OemHelper() {
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return true;
        }
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    public static void openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /**
     * Opens whatever "Autostart" / "App launch" screen this ROM provides.
     * Order matters: the most specific known component first.
     */
    public static void openAutostartSettings(Activity activity) {
        if (tryStart(activity, "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")) {
            return;
        }
        if (tryStart(activity, "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity")) {
            return;
        }
        if (tryStart(activity, "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity")) {
            return;
        }
        if (tryStart(activity, "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")) {
            return;
        }
        if (tryStart(activity, "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")) {
            return;
        }
        if (tryStart(activity, "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity")) {
            return;
        }
        if (tryStart(activity, "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")) {
            return;
        }
        if (tryStart(activity, "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")) {
            return;
        }
        openAppDetails(activity);
    }

    private static boolean tryStart(Activity activity, String packageName, String className) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
