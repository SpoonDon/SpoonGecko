package com.spoongecko.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public class SpoonGeckoApp extends Application {
    private static final String TAG = "SpoonGeckoApp";
    private static GeckoRuntime sRuntime;
    private static final String BLOCKER_ID = "blocker@spoongecko.app";
    private static final String BLOCKER_URI = "resource://android/assets/extensions/blocker/";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: enter");
        try {
            GeckoRuntime rt = getRuntime(this);
            Log.d(TAG, "onCreate: runtime created");
            installBuiltInExtensions(rt);
            Log.d(TAG, "onCreate: extensions installed");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate: GeckoRuntime creation FAILED", t);
            // Write this to the crash log as well
            try {
                java.io.File file = new java.io.File(getExternalFilesDir(null), "crash.log");
                java.io.FileWriter writer = new java.io.FileWriter(file, true);
                writer.write("=== RUNTIME CRASH ===\n");
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                writer.write(sw.toString());
                writer.write("\n\n");
                writer.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to write runtime crash log", e);
            }
        }
        Log.d(TAG, "onCreate: exit");
    }

    public static synchronized GeckoRuntime getRuntime(Context context) {
        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(
                context.getApplicationContext(),
                new GeckoRuntimeSettings.Builder().build()
            );
        }
        return sRuntime;
    }

    private void installBuiltInExtensions(GeckoRuntime rt) {
        rt.getWebExtensionController()
          .ensureBuiltIn(BLOCKER_URI, BLOCKER_ID)
          .accept(
              ext -> Log.d(TAG, "Extension ready: " + ext.id),
              err -> Log.e(TAG, "Extension install failed", err)
          );
    }
}
