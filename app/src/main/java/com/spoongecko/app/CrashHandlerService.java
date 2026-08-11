package com.spoongecko.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.CrashReporter;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class CrashHandlerService extends Service {

    private static final String LOGTAG = "CrashHandler";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (GeckoRuntime.ACTION_CRASHED.equals(intent.getAction())) {
            String minidumpPath = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH);
            String extrasPath = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH);

            if (minidumpPath != null && extrasPath != null) {
                File minidump = new File(minidumpPath);
                File extras = new File(extrasPath);
                try {
                    CrashReporter.sendCrashReport(this, minidump, extras, "SpoonGecko");
                } catch (IOException | URISyntaxException e) {
                    Log.e(LOGTAG, "Failed to send crash report", e);
                }
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
