package com.spoongecko.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Foreground service that keeps the process in the foreground-service OOM
 * bucket while the browser is running, plus a watchdog alarm that restarts
 * the service if the OEM kills it.
 */
public final class BrowserKeepAliveService extends Service {

    private static final String CHANNEL_ID = "spoongecko_keepalive";
    private static final int NOTIFICATION_ID = 1;
    private static final int RESTART_ALARM_REQUEST = 42;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BrowserKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, BrowserKeepAliveService.class));
        } catch (Throwable ignored) {
            // best effort
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StartupLog.i("BrowserKeepAliveService.onStartCommand");
        createChannel();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            StartupLog.i("BrowserKeepAliveService: startForeground OK");
        } catch (Throwable t) {
            StartupLog.e("startForeground failed", t);
        }

        // Watchdog: schedule a restart alarm in case the process is killed.
        scheduleWatchdog();

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swiped the app away from recents. Restart shortly.
        scheduleWatchdog();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        StartupLog.i("BrowserKeepAliveService.onDestroy — scheduling restart");
        scheduleWatchdog();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent i = new Intent(getApplicationContext(), BrowserKeepAliveService.class);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getService(
                    getApplicationContext(), RESTART_ALARM_REQUEST, i, piFlags);

            long triggerAt = SystemClock.elapsedRealtime() + 15_000L;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            StartupLog.i("Watchdog alarm scheduled");
        } catch (Throwable t) {
            StartupLog.e("scheduleWatchdog failed", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Browser session",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SpoonGecko")
                .setContentText("Browser session active")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
