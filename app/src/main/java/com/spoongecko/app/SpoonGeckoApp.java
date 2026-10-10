package com.spoongecko.app;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;

/**
 * Application entry point and single owner of the process-wide GeckoRuntime.
 */
public final class SpoonGeckoApp extends Application {

    private static final String BLOCKER_ID = "blocker@spoongecko.app";
    private static final String BLOCKER_URI =
            "resource://android/assets/extensions/blocker/";

    @Nullable
    private static volatile GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        StartupLog.init(this);
        StartupLog.i("Application.onCreate enter");
        try {
            GeckoRuntime rt = getRuntime(this);
            installBuiltInExtensions(rt);
            StartupLog.i("Application.onCreate: runtime ready");
        } catch (Throwable t) {
            StartupLog.e("Application.onCreate: runtime init failed", t);
        }
        StartupLog.i("Application.onCreate exit");
    }

    @NonNull
    public static GeckoRuntime getRuntime(@NonNull Context context) {
        GeckoRuntime local = sRuntime;
        if (local != null) return local;

        synchronized (SpoonGeckoApp.class) {
            local = sRuntime;
            if (local == null) {
                StartupLog.i("GeckoRuntime.create: begin");
                Context appCtx = context.getApplicationContext();
                GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                        .consoleOutput(BuildConfig.DEBUG)
                        .debugLogging(BuildConfig.DEBUG)
                        .build();
                local = GeckoRuntime.create(appCtx, settings);
                sRuntime = local;
                StartupLog.i("GeckoRuntime.create: OK");
            }
        }
        return local;
    }

    private static void installBuiltInExtensions(@NonNull GeckoRuntime rt) {
        try {
            rt.getWebExtensionController()
                    .ensureBuiltIn(BLOCKER_URI, BLOCKER_ID)
                    .accept(
                            ext -> StartupLog.i("Extension ready: " + ext.id),
                            err -> StartupLog.e("Extension install failed", err));
        } catch (Throwable t) {
            StartupLog.e("installBuiltInExtensions crashed", t);
        }
    }
}
