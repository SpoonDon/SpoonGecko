package com.spoongecko.app;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public class SpoonGeckoApp extends Application {
    private static GeckoRuntime sRuntime;
    private static final String BLOCKER_ID = "blocker@spoongecko.app";
    private static final String BLOCKER_URI = "resource://android/assets/extensions/blocker/";

    @Override
    public void onCreate() {
        super.onCreate();
        GeckoRuntime rt = getRuntime();
        installBuiltInExtensions(rt);
    }

    public static synchronized GeckoRuntime getRuntime() {
        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(
                // The Application context will be set later
                null, 
                new GeckoRuntimeSettings.Builder().build()
            );
        }
        return sRuntime;
    }

    // This must be called from an Activity to provide a valid context
    public static synchronized GeckoRuntime getRuntime(android.content.Context context) {
        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(context.getApplicationContext(), new GeckoRuntimeSettings.Builder().build());
        }
        return sRuntime;
    }

    private void installBuiltInExtensions(GeckoRuntime rt) {
        rt.getWebExtensionController()
          .ensureBuiltIn(BLOCKER_URI, BLOCKER_ID)
          .accept(
              ext -> { /* Extension is ready */ },
              err -> { /* Handle error */ }
          );
    }
}
