package com.spoongecko.app;

import android.app.Activity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

final class VaultSessionBinder {

    static final String NATIVE_APP = "spoonvault";
    private static final String EXTENSION_URI = "resource://android/assets/vault_extension/";
    private static final String EXTENSION_ID = "vault@spoongecko.app";

    private VaultSessionBinder() {}

    static void registerExtension(GeckoRuntime runtime) {
        runtime.getWebExtensionController().ensureBuiltIn(EXTENSION_URI, EXTENSION_ID);
    }

    static void attach(Activity activity, GeckoSession session) {
        GeckoRuntime runtime = SpoonGeckoApplication.getRuntime();
        if (runtime == null) return;
        WebExtensionController controller = runtime.getWebExtensionController();
        controller.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).accept(
                extension -> {
                    WebExtension.SessionController sessionController =
                            controller.getSessionController(session);
                    sessionController.setMessageDelegate(
                            extension,
                            new VaultMessageDelegate(activity),
                            NATIVE_APP);
                },
                error -> {
                }
        );
    }
}
