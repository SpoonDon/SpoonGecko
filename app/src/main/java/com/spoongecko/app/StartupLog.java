
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
 * File-based diagnostic logger. Writes timestamped lines to
 * getExternalFilesDir(null)/startup.log so the user can read what happened
 * after a crash or system kill, without adb or root.
 *
 * Also mirrors everything to logcat under tag SpoonGecko.
 */
public final class StartupLog {

    private static final String TAG = "SpoonGecko";
    private static final String FILE_NAME = "startup.log";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Nullable
    private static volatile File logFile;

    private StartupLog() {
    }

    public static void init(@NonNull Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                Log.e(TAG, "StartupLog: external files dir null");
                return;
            }
            File f = new File(dir, FILE_NAME);
            FileWriter w = new FileWriter(f, false);
            w.write("=== SpoonGecko startup log " + FMT.format(new Date()) + " ===\n");
            w.close();
            logFile = f;
        } catch (IOException e) {
            Log.e(TAG, "StartupLog.init failed", e);
        }
    }

    public static void i(@NonNull String msg) {
        write("I", msg, null);
    }

    public static void e(@NonNull String msg, @Nullable Throwable t) {
        write("E", msg, t);
    }

    private static void write(@NonNull String level,
                              @NonNull String msg,
                              @Nullable Throwable t) {
        String stamp = FMT.format(new Date());
        String line = stamp + " " + level + " " + msg + (t == null ? "" : " : " + t);

        if ("E".equals(level)) {
            Log.e(TAG, msg, t);
        } else {
            Log.i(TAG, msg);
        }

        File f = logFile;
        if (f == null) return;

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
