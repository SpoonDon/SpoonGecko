package com.spoongecko.browser;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Application entry point.
 *
 * GeckoView spawns helper processes (content, GPU, socket). Android
 * instantiates this Application class inside each of them too. The
 * GeckoRuntime must only be created ONCE, in the main process. Creating it
 * in a helper process corrupts GeckoView's runtime and kills the app with
 * a native abort — no Java stack trace, no crash handler firing.
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
            // Helper process: leave GeckoView alone. Do not create a runtime,
            // do not touch the notification channel beyond what Android needs.
            CrashLogger.breadcrumb(this, "app.onCreate helper — runtime skipped");
            return;
        }

        try {
            CrashLogger.breadcrumb(this, "runtime.create begin");
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .remoteDebuggingEnabled(false)
                    .build();
            sRuntime = GeckoRuntime.create(this, settings);
            CrashLogger.breadcrumb(this, "runtime.create ok");
        } catch (Throwable t) {
            CrashLogger.breadcrumb(this,
                    "runtime.create FAILED: " + t.getClass().getName()
                            + ": " + t.getMessage());
            android.util.Log.e("SpoonGecko", "GeckoRuntime.create failed", t);
        }
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

    /** Best-effort process name lookup across API levels. */
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
