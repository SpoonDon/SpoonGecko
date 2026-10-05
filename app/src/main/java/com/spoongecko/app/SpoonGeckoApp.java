package com.spoongecko.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Application entry point and single owner of the process-wide GeckoRuntime.
 *
 * The runtime is created eagerly in onCreate. If for any reason onCreate did
 * not run (manifest misconfiguration, crash earlier in startup), getRuntime
 * lazily creates it using the Application context so callers still succeed
 * instead of receiving a null and crashing on a native call.
 */
public final class SpoonGeckoApp extends Application {

    private static final String TAG = "SpoonGecko";

    @Nullable
    private static volatile GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            getRuntime(this);
            Log.i(TAG, "GeckoRuntime ready");
        } catch (Throwable t) {
            // Do not crash the Application on runtime init failure. Let the
            // Activity surface the problem in a controlled way.
            Log.e(TAG, "GeckoRuntime failed to initialize", t);
        }
    }

    @NonNull
    public static GeckoRuntime getRuntime(@NonNull Context context) {
        GeckoRuntime local = sRuntime;
        if (local != null) {
            return local;
        }
        synchronized (SpoonGeckoApp.class) {
            local = sRuntime;
            if (local == null) {
                Context appCtx = context.getApplicationContext();
                GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                        .consoleOutput(BuildConfig.DEBUG)
                        .debugLogging(BuildConfig.DEBUG)
                        .build();
                local = GeckoRuntime.create(appCtx, settings);
                sRuntime = local;
            }
        }
        return local;
    }
}
