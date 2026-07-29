package com.spoongecko.app

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeManager {
    private var runtime: GeckoRuntime? = null

    fun getRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val cbSettings = ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                .build()

            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .contentBlocking(cbSettings)
                .extensionsProcessEnabled(true) // Sandboxes extensions in their own RAM pool
                .crashReportingEnabled(false)   // Optimization: Prevents background network wakeups
                .build()

            runtime = GeckoRuntime.create(context.applicationContext, runtimeSettings)
        }
        return runtime!!
    }

    // Optimization: Graceful Shutdown
    // Cleanly kills the C++ engine threads so OEMs don't flag the app as a "zombie" battery drainer
    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
