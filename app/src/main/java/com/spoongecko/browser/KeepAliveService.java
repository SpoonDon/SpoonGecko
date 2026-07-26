package com.spoongecko.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "spoongecko_keepalive";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
        // startForeground is performed in onStartCommand for best compatibility
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, createNotification());
        } catch (IllegalStateException ignored) {
            // Best-effort: some OEMs may restrict startForeground here
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        // Do not restart directly here; rely on START_STICKY or schedule via AlarmManager/JobScheduler if needed
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Spoon Gecko Keep Alive",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the browser alive in background");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        int color = 0;
        try { color = ContextCompat.getColor(this, R.color.primary); } catch (Exception ignored) {}

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Spoon Gecko")
                .setContentText("Browser is running in background")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setColor(color)
                .build();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;

        try {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpoonGecko:KeepAlive");
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            }
        } catch (SecurityException se) {
            wakeLock = null;
        } catch (Exception ignored) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        } finally {
            wakeLock = null;
        }
    }
}
