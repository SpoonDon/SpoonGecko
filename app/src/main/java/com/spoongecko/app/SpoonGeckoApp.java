
package com.spoongecko.app;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Application entry point. Owns the single, process-wide GeckoRuntime.
 *
 * GeckoView requires the runtime to exist before any GeckoSession is
 * constructed. Creating it here (in Application.onCreate) guarantees that
 * ordering and avoids white-page and content-process failures on aggressive
 * OEM ROMs that reap idle background processes.
 *
 * Deliberately does NOT start any foreground service here.
 */
public final class SpoonGeckoApp extends Application {

    private static final String TAG = "SpoonGecko";

    private static GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();

        if (sRuntime != null) {
            return;
        }

        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                // Native Gecko logs are useful in debug and very noisy in
                // release. Gate on BuildConfig.DEBUG.
                .consoleOutput(BuildConfig.DEBUG)
                .build();

        sRuntime = GeckoRuntime.create(this, settings);
        Log.i(TAG, "GeckoRuntime created");
    }

    @NonNull
    public static GeckoRuntime getRuntime() {
        GeckoRuntime runtime = sRuntime;
        if (runtime == null) {
            throw new IllegalStateException(
                    "GeckoRuntime not initialized. Check that SpoonGeckoApp is "
                            + "declared via android:name in AndroidManifest.xml.");
        }
        return runtime;
    }
}
