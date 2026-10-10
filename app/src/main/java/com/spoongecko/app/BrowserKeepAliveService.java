package com.spoongecko.app;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

public class BrowserKeepAliveService extends Service {
    private static final String CHANNEL_ID = "spoongecko_keepalive";
    private static final int NOTIFICATION_ID = 1;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BrowserKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = buildNotification();
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        scheduleRestartAlarm();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Browser Session", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpoonGecko")
            .setContentText("Browser session active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build();
    }

    private void scheduleRestartAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(this, BrowserKeepAliveService.class);
        PendingIntent pi = PendingIntent.getService(this, 0, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // Use setAlarmClock as it is respected by aggressive OEMs
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(
            System.currentTimeMillis() + 5000, pi), pi);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
