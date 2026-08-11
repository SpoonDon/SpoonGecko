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
        // Content blocking configuration
        val cbSettings = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.AD or
                ContentBlocking.AntiTracking.ANALYTIC or
                ContentBlocking.AntiTracking.SOCIAL or
                ContentBlocking.AntiTracking.CRYPTOMINING or
                ContentBlocking.AntiTracking.FINGERPRINTING
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookiePurging(true)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            .strictSocialTrackingProtection(true)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .bounceTrackingProtectionMode(1) // Mode 1 = enabled
            .emailTrackerBlockingPrivateMode(true)
            .build()

        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .contentBlocking(cbSettings)
            .extensionsProcessEnabled(true)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .loginAutofillEnabled(true)
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            .javaScriptEnabled(true)
            .webFontsEnabled(true)
            .globalPrivacyControlEnabled(true)
            .fissionEnabled(true) // Site isolation for security
            .automaticFontSizeAdjustment(true)
            .forceUserScalableEnabled(true) // Override user-scalable=no
            .inputAutoZoomEnabled(true)
            .doubleTapZoomingEnabled(true)
            .lowMemoryDetection(true)
            .build()

        return GeckoRuntime.create(appContext, runtimeSettings)
    }

    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
