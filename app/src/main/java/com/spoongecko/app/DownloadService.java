package com.spoongecko.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadService extends Service {

    public static final String ACTION_START = "com.spoongecko.app.action.DOWNLOAD_START";
    public static final String ACTION_CANCEL = "com.spoongecko.app.action.DOWNLOAD_CANCEL";
    public static final String EXTRA_ID = "download_id";

    private static final String CHANNEL_ID = "download_progress_channel";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, ActiveDownload> ACTIVE = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    public static void enqueue(Context context, String filename, String mime, long totalBytes, InputStream body) {
        int id = NEXT_ID.getAndIncrement();
        ACTIVE.put(id, new ActiveDownload(id, filename, mime, totalBytes, body));
        Intent intent = new Intent(context, DownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void cancel(Context context, int id) {
        Intent intent = new Intent(context, DownloadService.class)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        int id = intent.getIntExtra(EXTRA_ID, -1);
        if (id < 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            ActiveDownload ad = ACTIVE.get(id);
            if (ad != null) {
                ad.cancelled = true;
                closeQuietly(ad.input);
                NotificationManagerCompat.from(this).cancel(id);
            }
            stopIfIdle();
            return START_NOT_STICKY;
        }

        ActiveDownload ad = ACTIVE.get(id);
        if (ad == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, buildProgressNotification(ad),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(id, buildProgressNotification(ad));
        }

        EXECUTOR.execute(() -> performDownload(ad));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        for (ActiveDownload ad : ACTIVE.values()) {
            ad.cancelled = true;
            closeQuietly(ad.input);
        }
        ACTIVE.clear();
        super.onDestroy();
    }

    private void performDownload(ActiveDownload ad) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, ad.filename);
        values.put(MediaStore.Downloads.MIME_TYPE, ad.mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("insert returned null");
            ad.uri = uri;

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("openOutputStream returned null");

                byte[] buffer = new byte[8192];
                int n;
                long lastUpdate = 0;
                while ((n = ad.input.read(buffer)) != -1) {
                    if (ad.cancelled) throw new IOException("cancelled");
                    out.write(buffer, 0, n);
                    ad.copied += n;
                    long now = SystemClock.uptimeMillis();
                    if (now - lastUpdate > 200) {
                        notifyProgress(ad);
                        lastUpdate = now;
                    }
                }
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);

            notifyCompleted(ad);
        } catch (IOException e) {
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            }
            notifyFinished(ad, ad.cancelled
                    ? getString(R.string.download_canceled_text)
                    : getString(R.string.download_failed_text));
        } finally {
            closeQuietly(ad.input);
            ACTIVE.remove(ad.id);
            stopIfIdle();
        }
    }

    private void notifyProgress(ActiveDownload ad) {
        NotificationManagerCompat.from(this).notify(ad.id, buildProgressNotification(ad));
    }

    private Notification buildProgressNotification(ActiveDownload ad) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(ad.filename)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        if (ad.totalBytes > 0) {
            int percent = (int) (ad.copied * 100 / ad.totalBytes);
            builder.setProgress(100, percent, false);
            builder.setContentText(percent + "%");
        } else {
            builder.setProgress(0, 0, true);
            builder.setContentText(getString(R.string.download_started));
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.download_cancel), cancelPendingIntent(ad.id));
        return builder.build();
    }

    private void notifyCompleted(ActiveDownload ad) {
        Intent open = new Intent(Intent.ACTION_VIEW);
        open.setDataAndType(ad.uri, ad.mime);
        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, ad.id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(ad.filename)
                .setContentText(getString(R.string.download_complete_text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build();
        NotificationManagerCompat.from(this).notify(ad.id, notification);
    }

    private void notifyFinished(ActiveDownload ad, String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(ad.filename)
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        NotificationManagerCompat.from(this).notify(ad.id, notification);
    }

    private PendingIntent cancelPendingIntent(int id) {
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id);
        return PendingIntent.getService(this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void stopIfIdle() {
        if (ACTIVE.isEmpty()) stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.download_notification_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static class ActiveDownload {
        final int id;
        final String filename;
        final String mime;
        final long totalBytes;
        final InputStream input;
        volatile boolean cancelled;
        long copied;
        Uri uri;

        ActiveDownload(int id, String filename, String mime, long totalBytes, InputStream input) {
            this.id = id;
            this.filename = filename;
            this.mime = mime;
            this.totalBytes = totalBytes;
            this.input = input;
        }
    }
}
