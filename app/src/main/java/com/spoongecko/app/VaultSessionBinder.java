package com.spoongecko.app;

import android.app.Activity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;

import java.lang.ref.WeakReference;

final class VaultSessionBinder {

    static final String NATIVE_APP = "spoonvault";
    private static final String EXTENSION_URI = "resource://android/assets/vault_extension/";
    private static final String EXTENSION_ID = "vault@spoongecko.app";
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);

    private VaultSessionBinder() {}

    static void setCurrentActivity(Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    static Activity currentActivity() {
        return currentActivity.get();
    }

    static void registerExtension(Activity activity, GeckoRuntime runtime) {
        setCurrentActivity(activity);
        runtime.getWebExtensionController().ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).accept(
                extension -> extension.setMessageDelegate(
                        new VaultMessageDelegate(), NATIVE_APP),
                error -> {
                }
        );
    }
}
