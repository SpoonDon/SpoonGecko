package com.spoongecko.app;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Application entry point and single owner of the process-wide GeckoRuntime.
 * Diagnostic build: every step is written to StartupLog.
 */
public final class SpoonGeckoApp extends Application {

    @Nullable
    private static volatile GeckoRuntime sRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        StartupLog.init(this);
        StartupLog.i("Application.onCreate enter");
        try {
            getRuntime(this);
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
                        .consoleOutput(true)
                        .debugLogging(true)
                        .build();
                local = GeckoRuntime.create(appCtx, settings);
                sRuntime = local;
                StartupLog.i("GeckoRuntime.create: OK");
            }
        }
        return local;
    }
}
