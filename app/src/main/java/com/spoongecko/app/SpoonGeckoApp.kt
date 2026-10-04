package com.spoongecko.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.spoongecko.app.browser.GeckoRuntimeHolder

class SpoonGeckoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GeckoRuntimeHolder.init(this)
        createNotificationChannel()
        // NOTE: KeepAliveService is intentionally NOT started here.
        // Starting a dataSync/specialUse FGS from Application.onCreate()
        // throws ForegroundServiceStartNotAllowedException on Android 12+,
        // and HyperOS often swallows the exception and kills the process.
        // It is started from MainActivity.onStart() instead.
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
