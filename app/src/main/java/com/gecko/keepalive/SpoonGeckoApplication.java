package com.gecko.keepalive;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public class SpoonGeckoApplication extends Application {
    private static GeckoRuntime sGeckoRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        if (sGeckoRuntime == null) {
            GeckoRuntimeSettings.Builder settingsBuilder = new GeckoRuntimeSettings.Builder();
            settingsBuilder.javaScriptEnabled(true);
            settingsBuilder.remoteDebuggingEnabled(false);
            settingsBuilder.webFontsEnabled(true);
            settingsBuilder.automaticFontSizeAdjustment(true);
            settingsBuilder.aboutConfigEnabled(false);
            sGeckoRuntime = GeckoRuntime.create(this, settingsBuilder.build());
        }
    }

    public static GeckoRuntime getGeckoRuntime() {
        return sGeckoRuntime;
    }
}
