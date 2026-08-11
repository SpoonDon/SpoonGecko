package com.spoongecko.app;

import android.content.Context;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public class GeckoRuntimeManager {
    private static GeckoRuntime runtime;

    public static synchronized GeckoRuntime getRuntime(Context context) {
        if (runtime == null) {
            ContentBlocking.Settings cbSettings = new ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.AD | ContentBlocking.AntiTracking.ANALYTIC | ContentBlocking.AntiTracking.SOCIAL | ContentBlocking.AntiTracking.CRYPTOMINING | ContentBlocking.AntiTracking.FINGERPRINTING)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .cookiePurging(true)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .strictSocialTrackingProtection(true)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .queryParameterStrippingEnabled(true)
                    .queryParameterStrippingPrivateBrowsingEnabled(true)
                    .bounceTrackingProtectionMode(1)
                    .emailTrackerBlockingPrivateMode(true)
                    .build();

            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .contentBlocking(cbSettings)
                    .extensionsProcessEnabled(true)
                    .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
                    .loginAutofillEnabled(true)
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL) // Required for local HTTP
                    .javaScriptEnabled(true)
                    .webFontsEnabled(true)
                    .globalPrivacyControlEnabled(true)
                    .fissionEnabled(true)
                    .automaticFontSizeAdjustment(true)
                    .forceUserScalableEnabled(true)
                    .inputAutoZoomEnabled(true)
                    .doubleTapZoomingEnabled(true)
                    .lowMemoryDetection(true)
                    .isolatedProcessEnabled(true)
                    .appZygoteProcessEnabled(true)
                    .largeKeepaliveFactor(2)
                    .build();

            runtime = GeckoRuntime.create(context.getApplicationContext(), settings);
        }
        return runtime;
    }

    public static void shutdown() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }
}
