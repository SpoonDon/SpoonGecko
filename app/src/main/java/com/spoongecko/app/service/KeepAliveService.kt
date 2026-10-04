package com.spoongecko.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spoongecko.app.MainActivity
import com.spoongecko.app.R

/**
 * Foreground "keep-alive" service.
 *
 * Why we need it: OEM skins (MIUI, ColorOS, Funtouch, One UI, EMUI, OxygenOS)
 * routinely kill backgrounded processes even if the user had a live tab open.
 * A foreground service with START_STICKY gives us the best available signal
 * to the OS that the process is user-visible work, and lets the system
 * respawn the service (and thus us) after aggressive cleanup.
 *
 * The notification is IMPORTANCE_MIN so it doesn't buzz or badge.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: ask the OS to recreate this service after a kill.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away, ask the system to respawn us shortly.
        val restart = Intent(applicationContext, KeepAliveService::class.java)
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getService(
            applicationContext, 1, restart, flags
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarm.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 2_000L,
            pending
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setSmallIcon(R.drawable.ic_stat_gecko)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "spoongecko_keepalive"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
