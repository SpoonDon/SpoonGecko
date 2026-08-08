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
    .useMultiprocess(true)
    .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
    .build()
            runtime = GeckoRuntime.create(context.applicationContext, runtimeSettings)
            runtime?.delegate = object : GeckoRuntime.Delegate {
    override fun onShutdown() {
        runtime = null
    }
}
        }
        return runtime!!
    }

    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
