package com.spoongecko.app;

import android.app.Activity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

final class VaultSessionBinder {

    static final String NATIVE_APP = "spoonvault";
    private static WebExtension vaultExtension;

    private VaultSessionBinder() {
    }

    static void registerExtension(GeckoRuntime runtime) {
        if (vaultExtension != null) return;
        vaultExtension = new WebExtension(
                "resource://android/assets/vault_extension/",
                "vault@spoongecko.app",
                WebExtension.Flags.ALLOW_CONTENT_MESSAGING);
        runtime.registerWebExtension(vaultExtension);
    }

    static void attach(Activity activity, GeckoSession session) {
        session.setMessageDelegate(new VaultMessageDelegate(activity), NATIVE_APP);
    }
}
