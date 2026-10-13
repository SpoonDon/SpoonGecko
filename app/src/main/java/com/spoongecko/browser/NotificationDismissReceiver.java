package com.spoongecko.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires when the user swipes the KeepAliveService notification away.
 *
 * Android 14+ made foreground-service notifications user-dismissible even
 * when setOngoing(true) is set. To preserve the previous "persistent
 * notification" UX (matching AdGuard et al.), we re-post the notification
 * immediately on dismissal by restarting KeepAliveService.
 *
 * The service itself is not stopped by the swipe on stock Android, but on
 * some OEM ROMs (HyperOS confirmed on some builds) the dismissal is treated
 * as a stop request. Restarting is the safe superset — if the service is
 * still alive, startForegroundService is a cheap no-op that re-posts the
 * notification; if it was killed, it comes back up cleanly.
 */
public class NotificationDismissReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        KeepAliveService.start(context);
    }
}
