package com.spoongecko.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic background extension update check (every 12 h, like Firefox for
 * Android). GeckoView calls must run on the main Looper, so the worker posts
 * the check there and waits for completion before reporting the result.
 */
public final class ExtensionUpdateWorker extends Worker {

    private static final String TAG = "SpoonGecko";
    private static final long UPDATE_TIMEOUT_SECONDS = 90L;

    public ExtensionUpdateWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        final Context ctx = getApplicationContext();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicBoolean failed = new AtomicBoolean(false);

        final boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
            if (isStopped()) {
                latch.countDown();
                return;
            }
            try {
                ExtensionManager em = ExtensionManager.get();
                em.initialize(ctx);   // creates a headless runtime if the process was cold
                if (em.isAutoUpdateEnabled()) {
                    em.checkForUpdatesAll((updated, errCount, checked) -> {
                        completed.set(true);
                        latch.countDown();
                    });
                } else {
                    completed.set(true);
                    latch.countDown();
                }
            } catch (Exception e) {
                Log.w(TAG, "extension update check failed", e);
                failed.set(true);
                latch.countDown();
            }
        });
        if (!posted) return Result.retry();

        try {
            if (!latch.await(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "extension update check timed out");
                return Result.retry();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        if (isStopped()) return Result.retry();
        return failed.get() || !completed.get() ? Result.retry() : Result.success();
    }
}
