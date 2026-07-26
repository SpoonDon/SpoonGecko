package com.spoongecko.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class KeepAliveRestartReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepAliveRestartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent svc = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restart KeepAliveService: " + e.getMessage());
        }
    }
}
