package com.spoongecko.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Restarts the keep-alive foreground service after device reboot.
 * Needed because OEM skins (HyperOS / ColorOS / OneUI) frequently kill
 * long-running background processes during the boot sequence.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                        || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);

        if (!isBoot) return;

        StartupLog.i("BootReceiver: device booted — starting keep-alive");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(
                        new Intent(context, BrowserKeepAliveService.class));
            } else {
                context.startService(
                        new Intent(context, BrowserKeepAliveService.class));
            }
        } catch (Throwable t) {
            StartupLog.e("BootReceiver: startForegroundService failed", t);
        }
    }
}
