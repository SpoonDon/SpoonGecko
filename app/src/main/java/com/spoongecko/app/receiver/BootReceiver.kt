package com.spoongecko.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spoongecko.app.service.KeepAliveService
import com.spoongecko.app.util.Prefs

/**
 * Restarts the keep-alive service after device reboot, quick boot, or
 * an app update. Without this, OEM skins that kill us on reboot would
 * leave the app fully dormant until the user manually opens it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.isKeepAliveEnabled(context)) return
        KeepAliveService.start(context)
    }
}
