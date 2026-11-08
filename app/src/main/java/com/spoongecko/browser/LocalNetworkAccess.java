package com.spoongecko.browser;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Android 17 (API 37) Local Network Protection.
 *
 * For apps that target SDK 37 or higher, connections to addresses on the
 * user's own network (router, modem, NAS, 192.168.x.x, 10.x.x.x, ISP gateways
 * in 100.64.x.x ...) are blocked by the OS unless the user has granted the
 * runtime permission below (it belongs to the "Nearby devices" group). It is
 * enforced in the networking stack, so it applies to GeckoView's own sockets
 * too; without the grant such a connection just times out.
 *
 * Older Android versions have no such permission and no such restriction, so
 * every method here is a no-op "granted" there.
 */
final class LocalNetworkAccess {

    private LocalNetworkAccess() {}

    static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private static final int FIRST_ENFORCING_SDK = 37;   // Android 17

    static boolean isEnforced() {
        return Build.VERSION.SDK_INT >= FIRST_ENFORCING_SDK;
    }

    static boolean isGranted(Context c) {
        return !isEnforced()
                || c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android would block local-network connections right now. */
    static boolean isMissing(Context c) {
        return isEnforced() && !isGranted(c);
    }

    /** True if loading this URL would be blocked until the user grants access. */
    static boolean needsPermissionFor(Context c, String url) {
        return isMissing(c) && LocalAddress.needsLocalNetworkPermission(LocalAddress.hostOf(url));
    }
}
