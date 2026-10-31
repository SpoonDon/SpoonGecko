package com.spoongecko.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Periodic background extension update check (every 12 h, like Firefox for
 * Android). GeckoView calls must run on a thread with a Looper, so the actual
 * work is posted to the main thread; the worker itself just triggers it.
 */
public final class ExtensionUpdateWorker extends Worker {

    private static final String TAG = "SpoonGecko";

    public ExtensionUpdateWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        final Context ctx = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ExtensionManager em = ExtensionManager.get();
                em.initialize(ctx);   // creates a headless runtime if the process was cold
                if (em.isAutoUpdateEnabled()) em.checkForUpdatesAll(null);
            } catch (Exception e) {
                Log.w(TAG, "extension update check failed", e);
            }
        });
        return Result.success();
    }
}
