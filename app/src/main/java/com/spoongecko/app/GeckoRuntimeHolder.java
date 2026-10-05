package com.spoongecko.app;

import android.content.Context;

import androidx.annotation.NonNull;

import org.mozilla.geckoview.GeckoRuntime;

/**
 * Process-wide singleton for the GeckoRuntime.
 *
 * GeckoView's runtime is expensive to create and is designed to outlive any
 * single Activity. Holding it here means rotation, tab switching, and
 * Activity re-creation all reuse the same engine instance.
 */
public final class GeckoRuntimeHolder {

    private static volatile GeckoRuntime sRuntime;

    private GeckoRuntimeHolder() {
        // no instances
    }

    @NonNull
    public static GeckoRuntime get(@NonNull Context context) {
        GeckoRuntime local = sRuntime;
        if (local == null) {
            synchronized (GeckoRuntimeHolder.class) {
                local = sRuntime;
                if (local == null) {
                    Context appCtx = context.getApplicationContext();
                    local = GeckoRuntime.create(appCtx);
                    sRuntime = local;
                }
            }
        }
        return local;
    }
}
