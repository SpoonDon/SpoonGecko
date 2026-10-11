package com.spoongecko.app;

import android.app.Application;
import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SpoonGeckoApp extends Application {

    private static final String TAG = "SpoonGecko";
    private static GeckoRuntime sRuntime;
    public static File LOG_FILE;

    @Override
    public void onCreate() {
        super.onCreate();

        // Create a publicly accessible log file in /sdcard/Download/
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            LOG_FILE = new File(dir, "spoongecko_debug.txt");
            FileWriter w = new FileWriter(LOG_FILE, false);
            w.write("=== SpoonGecko Debug Log ===\n");
            w.close();
        } catch (Throwable t) {
            Log.e(TAG, "Cannot create public log file", t);
        }

        log("Application.onCreate: enter");

        try {
            log("Creating GeckoRuntime with software WebRender...");
            sRuntime = GeckoRuntime.create(
                getApplicationContext(),
                new GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)
                    // ---- Force software WebRender ----
                    // Disables hardware WebRender entirely.
                    .preference("gfx.webrender.force-disabled", true)
                    // Uses the software compositor instead of the GPU.
                    .preference("gfx.webrender.software", true)
                    .build()
            );
            log("GeckoRuntime created OK");
        } catch (Throwable t) {
            log("GeckoRuntime FAILED: " + t);
            t.printStackTrace();
        }

        log("Application.onCreate: exit");
    }

    public static synchronized GeckoRuntime getRuntime(Context context) {
        if (sRuntime == null) {
            log("getRuntime: creating fallback with software WebRender");
            sRuntime = GeckoRuntime.create(
                context.getApplicationContext(),
                new GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)
                    .preference("gfx.webrender.force-disabled", true)
                    .preference("gfx.webrender.software", true)
                    .build()
            );
        }
        return sRuntime;
    }

    public static void log(String msg) {
        Log.d(TAG, msg);
        if (LOG_FILE == null) return;
        try {
            FileWriter w = new FileWriter(LOG_FILE, true);
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            w.write(ts + "  " + msg + "\n");
            w.flush();
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
