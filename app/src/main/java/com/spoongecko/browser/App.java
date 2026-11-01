package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * [C5] Startup-crash diagnosis without a PC.
 *
 * This project is built entirely on the GitHub web UI + GitHub Actions, so
 * there is no adb / logcat available. This Application class closes that gap:
 *
 *   1. It installs a default uncaught-exception handler that writes the full
 *      stack trace (plus device and ABI info) to files/last_crash.txt before
 *      the system kills the process.
 *
 *   2. showLastCrashIfAny(activity) is called by MainActivity at the very
 *      start of onCreate(): if the previous run crashed, the user gets a
 *      dialog containing the complete trace with Copy / Share buttons, so
 *      the report can be pasted into a bug report straight from the phone.
 */
public class App extends Application {

    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler system =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrash(App.this, thread, throwable);
            } catch (Throwable ignored) {
                // Never let the logger itself break crash delivery.
            }
            if (system != null) {
                system.uncaughtException(thread, throwable);
            }
        });
    }

    private static void writeCrash(Context c, Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        sb.append("SpoonGecko crash report\n");
        sb.append("time:   ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.US).format(new Date())).append('\n');
        sb.append("thread: ").append(thread.getName()).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" (Android ").append(Build.VERSION.RELEASE)
                .append(", API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abis:   ").append(Build.SUPPORTED_ABIS == null ? "?"
                : String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("\n----- stack trace -----\n").append(sw);
        File f = new File(c.getFilesDir(), CRASH_FILE);
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(sb.toString());
        }
    }

    /** Reads and deletes the pending crash report; null when there is none. */
    public static String takeCrashReport(Context c) {
        try {
            File f = new File(c.getFilesDir(), CRASH_FILE);
            if (!f.exists()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                int n;
                while (read < buf.length
                        && (n = in.read(buf, read, buf.length - read)) > 0) {
                    read += n;
                }
            }
            if (!f.delete()) f.deleteOnExit();
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Shows the previous crash (if any) with Copy / Share buttons. */
    public static void showLastCrashIfAny(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final String report = takeCrashReport(activity);
        if (report == null) return;

        TextView text = new TextView(activity);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(10f);
        int pad = dp(activity, 16);
        text.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(text, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        try {
            new AlertDialog.Builder(activity)
                    .setTitle("SpoonGecko crashed last time")
                    .setView(scroll)
                    .setPositiveButton("Share", (d, w) -> {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_SUBJECT, "SpoonGecko crash report");
                        i.putExtra(Intent.EXTRA_TEXT, report);
                        try {
                            activity.startActivity(Intent.createChooser(i, "Share crash report"));
                        } catch (Exception ignored) {}
                    })
                    .setNeutralButton("Copy", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("crash", report));
                        }
                        Toast.makeText(activity, "Crash report copied",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Dismiss", null)
                    .show();
        } catch (Exception ignored) {
            // A dying activity can refuse to show dialogs — never fatal.
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
