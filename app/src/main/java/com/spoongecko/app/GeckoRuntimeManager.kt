package com.spoongecko.app

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoRuntimeManager {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun getRuntime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: createRuntime(context.applicationContext).also { runtime = it }
        }
    }

    private fun createRuntime(appContext: Context): GeckoRuntime {
        val cbSettings = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .build()

        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .contentBlocking(cbSettings)
            .extensionsProcessEnabled(true)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .loginAutofillEnabled(true)
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            .build()

        return GeckoRuntime.create(appContext, runtimeSettings)
    }

    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
