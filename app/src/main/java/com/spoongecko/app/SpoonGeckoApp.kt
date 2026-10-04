package com.spoongecko.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.spoongecko.app.browser.GeckoRuntimeHolder
import com.spoongecko.app.service.KeepAliveService

/**
 * Application entry point.
 *
 * Responsibilities:
 *  - Initialize the GeckoRuntime once, on the process level, so it survives
 *    activity destruction and can be reused when the process is respawned by
 *    the system after an OEM kill.
 *  - Register the foreground service notification channel.
 *  - Kick off the keep-alive foreground service as early as possible.
 */
class SpoonGeckoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GeckoRuntimeHolder.init(this)
        createNotificationChannel()
        KeepAliveService.start(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                KeepAliveService.CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.keepalive_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
