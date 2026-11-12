package com.spoongecko.browser;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the app alive while files are downloading.
 *
 * It does no downloading itself (DownloadEngine does); its only job is to be a
 * foreground service whose notification is the progress notification of one of
 * the running downloads, so Android does not stop the process when the app is
 * in the background or the screen is off.
 *
 * If Android refuses to start it (an app in the background may not start a
 * foreground service), the downloads still run, just without that protection.
 */
public final class DownloadService extends Service {

    private static final String TAG = "SpoonGecko";

    /** Set while the service exists. UI thread only. */
    private static DownloadService instance;
    private static int pendingId;
    private static Notification pendingNotification;

    /** Makes {@code n} (posted under {@code id}) the foreground notification, starting the service if needed. */
    static void pin(Context ctx, int id, Notification n) {
        if (instance != null) {
            instance.promote(id, n);
            return;
        }
        pendingId = id;
        pendingNotification = n;
        try {
            ctx.startForegroundService(new Intent(ctx, DownloadService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "can't start the download service", e);
            pendingNotification = null;
        }
    }

    /** Nothing is downloading any more. */
    static void release() {
        pendingNotification = null;
        if (instance != null) instance.shutdown();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (action != null) {
            // Cancel / Retry buttons on a download notification.
            final DownloadEngine engine = DownloadEngine.get(this);
            final int id = intent.getIntExtra(DownloadEngine.EXTRA_ID, -1);
            if (DownloadEngine.ACTION_CANCEL.equals(action)) engine.cancel(id);
            else if (DownloadEngine.ACTION_RETRY.equals(action)) engine.retry(id);
            if (!engine.hasActiveJobs()) shutdown();
            return START_NOT_STICKY;
        }

        // Started by startForegroundService(): Android requires startForeground() promptly.
        final Notification n = pendingNotification;
        final int id = pendingId;
        pendingNotification = null;
        if (n == null) {
            promote(id, DownloadEngine.get(this).placeholderNotification());
            shutdown();                 // everything finished before we got started
        } else {
            promote(id, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Android 15 gives data-sync services a time limit; leave the foreground gracefully when it is hit. */
    public void onTimeout(int startId, int fgsType) {
        shutdown();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void promote(int id, Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(id, n);
            }
        } catch (RuntimeException e) {
            // Not allowed right now: keep the progress visible as an ordinary notification.
            Log.w(TAG, "startForeground failed", e);
            final NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(id, n);
        }
    }

    private void shutdown() {
        if (instance == this) instance = null;       // new downloads start a fresh service
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
