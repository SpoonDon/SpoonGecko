package com.spoongecko.browser;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton that manages WebExtension lifecycle for SpoonGecko.
 *
 * <p>Verified against GeckoView 157 Javadoc:
 * <ul>
 *   <li>{@link WebExtensionController#install(String, String)} is the current API;
 *       the single-arg overload is deprecated.</li>
 *   <li>{@code onInstallPrompt} was removed in v134 — we use
 *       {@code onInstallPromptRequest} via {@link ExtensionPromptDelegate}.</li>
 * </ul>
 */
public final class ExtensionManager {

    private static final String TAG = "ExtensionManager";
    private static ExtensionManager sInstance;

    private WebExtensionController mController;
    private final List<WebExtension> mExtensions = new ArrayList<>();
    private ExtensionListListener mListener;

    /** Callback fired whenever the installed-extension list changes. */
    public interface ExtensionListListener {
        void onExtensionsChanged(List<WebExtension> extensions);
    }

    private ExtensionManager() {}

    public static synchronized ExtensionManager getInstance() {
        if (sInstance == null) {
            sInstance = new ExtensionManager();
        }
        return sInstance;
    }

    /**
     * Must be called once after {@link GeckoRuntime} is created in the main
     * process only. The {@code activity} is used for install-permission dialogs.
     */
    public void init(Activity activity, GeckoRuntime runtime) {
        if (activity == null || runtime == null) {
            Log.e(TAG, "init: activity or runtime is null");
            return;
        }
        mController = runtime.getWebExtensionController();
        if (mController == null) {
            Log.e(TAG, "init: WebExtensionController unavailable");
            return;
        }
        mController.setPromptDelegate(new ExtensionPromptDelegate(activity));
        mController.setAddonManagerDelegate(new ExtensionAddonManagerDelegate(this));
        refresh();
    }

    public WebExtensionController getController() {
        return mController;
    }

    public void setListener(ExtensionListListener listener) {
        mListener = listener;
        if (listener != null) {
            listener.onExtensionsChanged(Collections.unmodifiableList(mExtensions));
        }
    }

    /** Re-query the installed extension list from GeckoView. */
    public void refresh() {
        if (mController == null) {
            return;
        }
        mController.list().accept(extensions -> {
            mExtensions.clear();
            if (extensions != null) {
                mExtensions.addAll(extensions);
            }
            notifyListener();
        }, throwable -> Log.e(TAG, "list() failed", throwable));
    }

    /**
     * Install an extension from a remote .xpi URL. The add-on must be signed
     * by Mozilla (AMO-signed). GeckoView downloads and verifies the signature.
     */
    public void installFromUrl(String url) {
        if (mController == null) {
            Log.e(TAG, "installFromUrl: controller not initialised");
            return;
        }
        mController.install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                .accept(extension -> {
                    Log.i(TAG, "Installed: " + extension.metaData.name);
                    refresh();
                }, throwable -> Log.e(TAG, "Install failed: " + url, throwable));
    }

    /**
     * Install a built-in extension bundled inside the APK's assets.
     * Built-in extensions do not need Mozilla signing.
     */
    public void installBuiltIn(String resourceUri, String extensionId) {
        if (mController == null) {
            return;
        }
        mController.ensureBuiltIn(resourceUri, extensionId)
                .accept(extension -> {
                    Log.i(TAG, "Built-in ensured: " + extension.metaData.name);
                    refresh();
                }, throwable -> Log.e(TAG, "ensureBuiltIn failed", throwable));
    }

    public void uninstall(WebExtension extension) {
        if (mController == null || extension == null) {
            return;
        }
        mController.uninstall(extension).accept(unused -> {
            Log.i(TAG, "Uninstalled: " + extension.metaData.name);
            refresh();
        }, throwable -> Log.e(TAG, "Uninstall failed", throwable));
    }

    public void enable(WebExtension extension) {
        if (mController == null || extension == null) {
            return;
        }
        mController.enable(extension, WebExtensionController.EnableSource.USER)
                .accept(updated -> {
                    Log.i(TAG, "Enabled: " + extension.metaData.name);
                    refresh();
                }, throwable -> Log.e(TAG, "Enable failed", throwable));
    }

    public void disable(WebExtension extension) {
        if (mController == null || extension == null) {
            return;
        }
        mController.disable(extension, WebExtensionController.EnableSource.USER)
                .accept(updated -> {
                    Log.i(TAG, "Disabled: " + extension.metaData.name);
                    refresh();
                }, throwable -> Log.e(TAG, "Disable failed", throwable));
    }

    private void notifyListener() {
        if (mListener != null) {
            mListener.onExtensionsChanged(Collections.unmodifiableList(mExtensions));
        }
    }
}
