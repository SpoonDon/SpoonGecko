package com.spoongecko.browser;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Application entry point.
 *
 * Owns the process-wide GeckoRuntime (GeckoView must be created once per process)
 * and creates the notification channel used by the keep-alive foreground service.
 */
public class GeckoApp extends Application {

    public static final String CHANNEL_KEEPALIVE = "spoongecko_keepalive";

    private static GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .build();

        sRuntime = GeckoRuntime.create(this, settings);
    }

    /** May return null only if the runtime failed to start. */
    public static GeckoRuntime getRuntime() {
        return sRuntime;
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_KEEPALIVE,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.keepalive_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
