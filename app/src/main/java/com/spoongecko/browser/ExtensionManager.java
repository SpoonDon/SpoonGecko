package com.spoongecko.browser;

import android.app.Activity;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton that owns the WebExtensionController for SpoonGecko.
 *
 * Verified against GeckoView 157 Javadoc:
 *   - WebExtensionController.install(String, String) is the current API
 *     (the single-arg overload is deprecated).
 *   - onInstallPrompt was removed in v134; use onInstallPromptRequest.
 *   - Built-in extensions are registered via ensureBuiltIn / installBuiltIn,
 *     not via the (now removed) WebExtension constructor + registerBuiltIn.
 */
public final class ExtensionManager {

    private static final String TAG = "ExtensionManager";
    private static ExtensionManager sInstance;

    private WebExtensionController mController;
    private final List<WebExtension> mExtensions = new ArrayList<>();
    private ExtensionListListener mListener;

    /**
     * The bundled scroll-reporter extension, resolved once after
     * ensureBuiltIn completes. {@link TabManager} uses this reference when
     * attaching a per-session message delegate.
     */
    private volatile WebExtension mScrollExtension;

    public interface ExtensionListListener {
        void onExtensionsChanged(List<WebExtension> extensions);
    }

    private ExtensionManager() {}

    public static synchronized ExtensionManager getInstance() {
        if (sInstance == null) sInstance = new ExtensionManager();
        return sInstance;
    }

    /** Must be called once per Activity instance, after GeckoRuntime exists. */
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

        if (mScrollExtension == null) {
            try {
                // Async: captures the WebExtension once it's ready.
                ScrollExtension.install(mController, extension -> {
                    mScrollExtension = extension;
                    Log.i(TAG, "scroll extension available: " + extension.id);
                });
            } catch (Throwable t) {
                Log.e(TAG, "failed to register built-in extensions", t);
            }
        }

        refresh();
    }

    /** Returns the scroll-reporter extension, or null if not yet installed. */
    public WebExtension getScrollExtension() {
        return mScrollExtension;
    }

    public void setListener(ExtensionListListener listener) {
        mListener = listener;
        if (listener != null) {
            listener.onExtensionsChanged(Collections.unmodifiableList(mExtensions));
        }
    }

    public void refresh() {
        if (mController == null) return;
        mController.list().accept(extensions -> {
            mExtensions.clear();
            if (extensions != null) {
                for (WebExtension ext : extensions) {
                    // The scroll reporter is internal — never show it in
                    // the Extensions dialog.
                    if (ext != null && ext.metaData != null
                            && ScrollExtension.EXTENSION_ID.equals(ext.id)) {
                        continue;
                    }
                    mExtensions.add(ext);
                }
            }
            if (mListener != null) {
                mListener.onExtensionsChanged(
                        Collections.unmodifiableList(mExtensions));
            }
        }, throwable -> Log.e(TAG, "list() failed", throwable));
    }

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

    public void uninstall(WebExtension extension) {
        if (mController == null || extension == null) return;
        mController.uninstall(extension).accept(unused -> {
            Log.i(TAG, "Uninstalled: " + extension.metaData.name);
            refresh();
        }, throwable -> Log.e(TAG, "Uninstall failed", throwable));
    }

    public void enable(WebExtension extension) {
        if (mController == null || extension == null) return;
        mController.enable(extension, WebExtensionController.EnableSource.USER)
                .accept(updated -> {
                    Log.i(TAG, "Enabled: " + extension.metaData.name);
                    refresh();
                }, throwable -> Log.e(TAG, "Enable failed", throwable));
    }

    public void disable(WebExtension extension) {
        if (mController == null || extension == null) return;
        mController.disable(extension, WebExtensionController.EnableSource.USER)
                .accept(updated -> {
                    Log.i(TAG, "Disabled: " + extension.metaData.name);
                    refresh();
                }, throwable -> Log.e(TAG, "Disable failed", throwable));
    }
}
