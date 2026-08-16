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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
            archiveCrashLocally(minidumpPath, extrasPath);
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void archiveCrashLocally(String minidumpPath, String extrasPath) {
        File crashDir = new File(getFilesDir(), "crashes");
        if (!crashDir.exists() && !crashDir.mkdirs()) {
            Log.e(LOGTAG, "Failed to create crash directory");
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        copyTo(crashDir, minidumpPath, "minidump_" + stamp + ".dmp");
        copyTo(crashDir, extrasPath, "extras_" + stamp + ".txt");
        Log.i(LOGTAG, "Crash dump archived locally: " + crashDir.getAbsolutePath());
    }

    private void copyTo(File dir, String sourcePath, String targetName) {
        if (sourcePath == null) return;
        File source = new File(sourcePath);
        if (!source.exists() || !source.isFile()) return;
        File target = new File(dir, targetName);
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            Log.e(LOGTAG, "Failed to archive crash file: " + sourcePath, e);
        }
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
