package com.spoongecko.browser;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Foreground service with an ongoing notification.
 *
 * This is the primary defence against the OS (and OEM cleaners) reclaiming the
 * process. A foreground service raises the process priority to the point where
 * the normal low-memory killer will not touch it, and it survives the user
 * swiping the task away because stopWithTask is false in the manifest.
 */
public class KeepAliveService extends Service {

    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_START = "com.spoongecko.browser.action.KEEPALIVE_START";
    private static final String ACTION_STOP = "com.spoongecko.browser.action.KEEPALIVE_STOP";

    public static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_START);
        try {
            context.startForegroundService(intent);
        } catch (Exception ignored) {
            // Background-start restrictions on some ROMs. Nothing we can do here.
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            try {
                stopForeground(true);
            } catch (Exception ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        return new Notification.Builder(this, GeckoApp.CHANNEL_KEEPALIVE)
                .setSmallIcon(R.drawable.ic_stat_spoon)
                .setContentTitle(getString(R.string.keepalive_title))
                .setContentText(getString(R.string.keepalive_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
