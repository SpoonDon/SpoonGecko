package com.spoongecko.browser;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;

/**
 * Application entry point.
 *
 * GeckoView spawns helper processes; this class must only create the
 * GeckoRuntime in the main process. Privacy settings live here so every
 * session inherits them.
 */
public class GeckoApp extends Application {

    public static final String CHANNEL_KEEPALIVE = "spoongecko_keepalive";

    private static GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);

        final String process = currentProcessName(this);
        final int pid = Process.myPid();
        final boolean mainProcess = getPackageName().equals(process);

        CrashLogger.breadcrumb(this,
                "app.onCreate pid=" + pid + " proc=" + process + " main=" + mainProcess);

        createNotificationChannel();

        if (!mainProcess) {
            CrashLogger.breadcrumb(this, "app.onCreate helper — runtime skipped");
            return;
        }

        try {
            CrashLogger.breadcrumb(this, "runtime.create begin");
            sRuntime = GeckoRuntime.create(this, buildRuntimeSettings());
            CrashLogger.breadcrumb(this, "runtime.create ok");
        } catch (Throwable t) {
            CrashLogger.breadcrumb(this,
                    "runtime.create FAILED: " + t.getClass().getName()
                            + ": " + t.getMessage());
            android.util.Log.e("SpoonGecko", "GeckoRuntime.create failed", t);
        }
    }

    // ------------------------------------------------------------------ settings

    private static GeckoRuntimeSettings buildRuntimeSettings() {
        // Strict Enhanced Tracking Protection + Total Cookie Protection +
        // every Safe Browsing list. This is the most aggressive built-in
        // configuration GeckoView exposes.
        ContentBlocking.Settings contentBlocking = new ContentBlocking.Settings.Builder()
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .cookieBehavior(
                        ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .safeBrowsing(ContentBlocking.SafeBrowsing.MALWARE
                        | ContentBlocking.SafeBrowsing.PHISHING
                        | ContentBlocking.SafeBrowsing.UNWANTED
                        | ContentBlocking.SafeBrowsing.HARMFUL)
                .build();

        return new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)

                // ---- Privacy: nothing phones home ----------------------------
                .telemetryEnabled(false)         // no Glean pings to Mozilla
                .crashHandler(null)              // no crash reports to Mozilla
                .debugLogging(false)             // no verbose Gecko logs
                .consoleOutput(false)            // no page console -> logcat
                .remoteDebuggingEnabled(false)   // no USB debug socket
                .aboutConfigEnabled(false)       // no about:config

                // ---- Privacy: signal to sites --------------------------------
                .globalPrivacyControl(true)      // GPC opt-out header

                // ---- Autofill: OFF, we own the vault -------------------------
                .loginAutofillEnabled(false)
                .autofillLoginsEnabled(false)
                .passwordAutofillEnabled(false)
                .passwordAutofillForOtherAppsEnabled(false)

                // ---- Compatibility -------------------------------------------
                .enterpriseRootsEnabled(true)    // use system CA store too
                .webManifest(true)               // PWA "Add to Home Screen"

                // ---- Content blocking ----------------------------------------
                .contentBlocking(contentBlocking)
                .build();
    }

    public static GeckoRuntime getRuntime() {
        return sRuntime;
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_KEEPALIVE,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.keepalive_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    static String currentProcessName(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String n = Application.getProcessName();
            if (n != null && !n.isEmpty()) return n;
        }
        int pid = Process.myPid();
        ActivityManager am =
                (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            try {
                for (ActivityManager.RunningAppProcessInfo p
                        : am.getRunningAppProcesses()) {
                    if (p.pid == pid) return p.processName;
                }
            } catch (Throwable ignored) {}
        }
        return ctx.getPackageName();
    }
}
