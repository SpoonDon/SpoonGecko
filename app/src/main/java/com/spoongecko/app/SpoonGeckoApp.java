package com.spoongecko.app;

import android.app.Application;
import android.util.Log;

import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Application entry point.
 *
 * Responsibilities (Stage 1):
 *   - Create exactly one GeckoRuntime, before any GeckoSession is constructed
 *     (landmine #5).
 *   - Expose it to the rest of the app via a static getter.
 *
 * Deliberately does NOT start any foreground service here (landmine #1).
 */
public final class SpoonGeckoApp extends Application {

    private static final String TAG = "SpoonGecko";

    @Nullable
    private static GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();

        if (sRuntime != null) {
            return;
        }

        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                // Landmine #7 — native Gecko crashes appear in logcat only with
                // consoleOutput enabled. Flip to false for release builds.
                .consoleOutput(true)
                .build();

        sRuntime = GeckoRuntime.create(this, settings);
        Log.i(TAG, "GeckoRuntime created");
    }

    @Nullable
    public static GeckoRuntime getRuntime() {
        return sRuntime;
    }
}
