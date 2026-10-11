package com.spoongecko.browser;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public class GeckoApp extends Application {

    public static final String CHANNEL_KEEPALIVE = "spoongecko_keepalive";

    private static GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        // Install the crash handler BEFORE anything else so an early failure
        // is still captured.
        CrashLogger.install(this);
        CrashLogger.breadcrumb(this, "app.onCreate begin");

        super.onCreate();

        createNotificationChannel();
        CrashLogger.breadcrumb(this, "app.channel ready");

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
                    "runtime.create FAILED: " + t.getClass().getSimpleName());
            android.util.Log.e("SpoonGecko", "GeckoRuntime.create failed", t);
            // Re-throw only if you want the app to die immediately; otherwise
            // leave sRuntime null and MainActivity will show a toast.
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
}
