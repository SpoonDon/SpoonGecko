package com.spoongecko.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.CrashReporter;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class CrashHandlerService extends Service {

    private static final String LOGTAG = "CrashHandler";
    private static final int NOTIFICATION_ID = 8001;
    private static final String CHANNEL_ID = "crash_reporter_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);

        if (intent == null || !GeckoRuntime.ACTION_CRASHED.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String minidumpPath = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH);
        final String extrasPath = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH);

        new Thread(() -> {
            if (minidumpPath != null && extrasPath != null) {
                File minidump = new File(minidumpPath);
                File extras = new File(extrasPath);
                try {
                    CrashReporter.sendCrashReport(this, minidump, extras, "SpoonGecko");
                } catch (IOException | URISyntaxException e) {
                    Log.e(LOGTAG, "Failed to send crash report", e);
                }
            }
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.browser_service_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.browser_service_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.browser_notification_title))
                .setContentText(getString(R.string.browser_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
