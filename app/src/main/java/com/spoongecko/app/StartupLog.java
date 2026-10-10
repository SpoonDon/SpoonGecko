package com.spoongecko.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * File-based diagnostic logger. Only active in debug builds — in release
 * builds every call becomes a no-op so that browsing history is never
 * written to disk.
 */
public final class StartupLog {

    private static final String TAG = "SpoonGecko";
    private static final String FILE_NAME = "startup.log";

    private static final ThreadLocal<SimpleDateFormat> FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                }
            };

    private static final Object LOCK = new Object();

    @Nullable
    private static volatile File logFile;
    private static volatile boolean enabled = false;

    private StartupLog() {
    }

    public static void init(@NonNull Context context) {
        if (!BuildConfig.DEBUG) return;
        enabled = true;
        synchronized (LOCK) {
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir == null) {
                    Log.e(TAG, "StartupLog: external files dir null");
                    return;
                }
                File f = new File(dir, FILE_NAME);
                FileWriter w = new FileWriter(f, false);
                w.write("=== SpoonGecko startup log "
                        + FMT.get().format(new Date()) + " ===\n");
                w.close();
                logFile = f;
            } catch (IOException e) {
                Log.e(TAG, "StartupLog.init failed", e);
            }
        }
    }

    public static void i(@NonNull String msg) {
        if (!enabled) return;
        write("I", msg, null);
    }

    public static void e(@NonNull String msg, @Nullable Throwable t) {
        if (!enabled) return;
        write("E", msg, t);
    }

    private static void write(@NonNull String level,
                              @NonNull String msg,
                              @Nullable Throwable t) {
        String stamp = FMT.get().format(new Date());
        String line = stamp + " " + level + " " + msg + (t == null ? "" : " : " + t);

        if ("E".equals(level)) {
            Log.e(TAG, msg, t);
        } else {
            Log.i(TAG, msg);
        }

        File f = logFile;
        if (f == null) return;

        synchronized (LOCK) {
            try {
                FileWriter w = new FileWriter(f, true);
                w.write(line);
                w.write("\n");
                if (t != null) {
                    for (StackTraceElement el : t.getStackTrace()) {
                        w.write("    at " + el.toString() + "\n");
                    }
                }
                w.flush();
                w.close();
            } catch (IOException ignored) {
                // Never let logging failures crash the app.
            }
        }
    }
}
