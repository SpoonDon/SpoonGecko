package com.spoongecko.browser;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service with an ongoing notification.
 *
 * Deliberately uses START_NOT_STICKY. START_STICKY makes Android re-create
 * the entire process (including Application.onCreate) whenever the OS kills
 * the service, which on this app means re-running GeckoRuntime.create() in a
 * restart loop. If the OS wants us dead, let us stay dead quietly and let
 * the user relaunch.
 */
public class KeepAliveService extends Service {

    private static final String TAG = "KeepAliveService";
    private static final int NOTIFICATION_ID = 1001;

    private static final String ACTION_START =
            "com.spoongecko.browser.action.KEEPALIVE_START";
    private static final String ACTION_STOP =
            "com.spoongecko.browser.action.KEEPALIVE_STOP";

    public static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            CrashLogger.breadcrumb(context, "keepalive.startForegroundService ok");
        } catch (Throwable t) {
            CrashLogger.breadcrumb(context,
                    "keepalive.startForegroundService FAILED: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.w(TAG, "startForegroundService failed", t);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "stop service failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.breadcrumb(this, "service.onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : "(null)";
        CrashLogger.breadcrumb(this, "service.onStartCommand action=" + action);

        if (ACTION_STOP.equals(action)) {
            try { stopForeground(true); } catch (Throwable ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification;
        try {
            notification = buildNotification();
        } catch (Throwable t) {
            CrashLogger.breadcrumb(this,
                    "service.buildNotification FAILED: " + t.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            CrashLogger.breadcrumb(this, "service.startForeground ok");
        } catch (Throwable t) {
            CrashLogger.breadcrumb(this,
                    "service.startForeground FAILED: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.e(TAG, "startForeground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        CrashLogger.breadcrumb(this, "service.onDestroy");
        super.onDestroy();
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
