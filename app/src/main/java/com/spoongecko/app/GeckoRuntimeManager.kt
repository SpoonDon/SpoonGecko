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
                .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                // CHANGED: Disable SafeBrowsing – it can block local/private IPs
                .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                .build()

            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .contentBlocking(cbSettings)
                .extensionsProcessEnabled(true)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
                // ADDED: Disable HTTPS-Only so http:// local IPs load directly
                .httpsOnlyMode(GeckoRuntimeSettings.HTTPS_ONLY_DISABLED)
                // ADDED: Allow cleartext (HTTP) connections at the engine level
                .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_INSECURE_CONNECTIONS_ENABLED)
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
