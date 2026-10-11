package com.spoongecko.browser;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Records Java crashes and lifecycle breadcrumbs so a user with no ADB access
 * can retrieve a report from inside the app after a silent death.
 *
 * Files land in  <external-files>/crashes/  (falling back to internal files/
 * if external storage is unavailable). Both locations are app-private; the
 * external one is reachable from the system Files app on most ROMs.
 */
public final class CrashLogger {

    private static final String TAG = "SpoonGecko";
    private static final String DIR = "crashes";
    private static final int MAX_REPORTS = 8;

    private CrashLogger() {}

    // ------------------------------------------------------------------ setup

    public static void install(Context context) {
        final Context appCtx = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(appCtx, "java", thread.getName(), throwable);
            } catch (Throwable ignored) {
                // never crash the crash path
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    // -------------------------------------------------------------- breadcrumbs

    public static void breadcrumb(Context context, String event) {
        try {
            File dir = dir(context.getApplicationContext());
            File log = new File(dir, "breadcrumbs.txt");
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date()) + "  " + event + "\n";
            try (FileWriter fw = new FileWriter(log, true)) {
                fw.write(line);
            }
            trim(dir);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------- access API

    /** Returns the newest crash report file, or null if none exists. */
    public static File latestReportFile(Context context) {
        File dir = dir(context.getApplicationContext());
        File[] files = dir.listFiles((d, name) ->
                name.startsWith("java-") || name.startsWith("breadcrumbs"));
        if (files == null || files.length == 0) return null;
        File newest = null;
        for (File f : files) {
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
    }

    // -------------------------------------------------------------- internals

    private static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File d = new File(base, DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void write(Context context, String kind, String thread,
                              Throwable t) {
        File dir = dir(context);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        File out = new File(dir, kind + "-" + stamp + ".txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("SpoonGecko crash report");
        pw.println("kind    : " + kind);
        pw.println("time    : " + new Date());
        pw.println("thread  : " + thread);
        pw.println("sdk     : " + Build.VERSION.SDK_INT);
        pw.println("device  : " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("abi     : " + (Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?"));
        pw.println("version : " + appVersion(context));
        pw.println("---------------- breadcrumbs ----------------");
        pw.print(readBreadcrumbs(dir));
        pw.println("---------------- stack trace ---------------");
        t.printStackTrace(pw);
        pw.flush();

        try (FileWriter fw = new FileWriter(out)) {
            fw.write(sw.toString());
        } catch (Exception e) {
            Log.e(TAG, "failed writing crash report", e);
        }

        trim(dir);
        Log.e(TAG, "crash written: " + out.getAbsolutePath());
    }

    private static String readBreadcrumbs(File dir) {
        File f = new File(dir, "breadcrumbs.txt");
        if (!f.exists()) return "(none)\n";
        try {
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int r = in.read(data);
                return new String(data, 0, Math.max(r, 0), "UTF-8");
            }
        } catch (Exception e) {
            return "(read failed: " + e + ")\n";
        }
    }

    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_REPORTS) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int excess = files.length - MAX_REPORTS;
        for (int i = 0; i < excess; i++) files[i].delete();
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
