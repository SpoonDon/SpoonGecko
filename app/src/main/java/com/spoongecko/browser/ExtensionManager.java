package com.spoongecko.browser;

import android.app.Activity;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExtensionManager {

    private static final String TAG = "ExtensionManager";
    private static ExtensionManager sInstance;

    private WebExtensionController mController;
    private final List<WebExtension> mExtensions = new ArrayList<>();
    private ExtensionListListener mListener;

    public interface ExtensionListListener {
        void onExtensionsChanged(List<WebExtension> extensions);
    }

    private ExtensionManager() {}

    public static synchronized ExtensionManager getInstance() {
        if (sInstance == null) sInstance = new ExtensionManager();
        return sInstance;
    }

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
                    if (ext != null && ext.metaData != null) {
                        mExtensions.add(ext);
                    }
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
