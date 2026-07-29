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
                .extensionsProcessEnabled(true)
                // MAGIC FIX 1: Force Dark Mode for web content to prevent white flashes
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK) 
                .build()

            runtime = GeckoRuntime.create(context.applicationContext, runtimeSettings)
        }
        return runtime!!
    }

    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
