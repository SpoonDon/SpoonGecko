package com.spoongecko.app;

import android.app.Application;
import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
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
            // Copy the GeckoView config YAML from assets to app files dir
            File configFile = new File(getFilesDir(), "geckoview-config.yaml");
            if (!configFile.exists()) {
                InputStream is = getAssets().open("geckoview-config.yaml");
                FileOutputStream fos = new FileOutputStream(configFile);
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                is.close();
            }
            log("Config file path: " + configFile.getAbsolutePath());

            log("Creating GeckoRuntime with config file...");
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder();
            builder.consoleOutput(true);
            builder.configFilePath(configFile.getAbsolutePath());
            sRuntime = GeckoRuntime.create(getApplicationContext(), builder.build());
            log("GeckoRuntime created OK");
        } catch (Throwable t) {
            log("GeckoRuntime FAILED: " + t);
            t.printStackTrace();
        }

        log("Application.onCreate: exit");
    }

    public static synchronized GeckoRuntime getRuntime(Context context) {
        if (sRuntime == null) {
            log("getRuntime: creating fallback");
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder();
            builder.consoleOutput(true);
            sRuntime = GeckoRuntime.create(context.getApplicationContext(), builder.build());
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
