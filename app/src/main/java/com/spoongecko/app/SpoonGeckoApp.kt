package com.spoongecko.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.spoongecko.app.browser.GeckoRuntimeHolder

/**
 * Application entry point.
 *
 * Responsibilities:
 *  - Initialize the GeckoRuntime once, on the process level, so it survives
 *    activity destruction and can be reused when the process is respawned by
 *    the system after an OEM kill.
 *  - Register the foreground service notification channel.
 *
 * NOTE: We deliberately do NOT start KeepAliveService here. On Android 14+
 * (API 34+) starting a dataSync foreground service from Application.onCreate
 * throws ForegroundServiceStartNotAllowedException on many ROMs, and on
 * Xiaomi/HyperOS it manifests as a silent process death with no logcat trail.
 * The service is started from MainActivity.onStart() instead, where the app
 * is unambiguously in the foreground and the start is legal.
 */
class SpoonGeckoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GeckoRuntimeHolder.init(this)
        createNotificationChannel()
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
