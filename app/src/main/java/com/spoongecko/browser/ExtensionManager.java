package com.spoongecko.browser;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.Nullable;

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

    private volatile WebExtension mScrollExtension;
    private ScrollExtensionListener mScrollListener;

    public interface ExtensionListListener {
        void onExtensionsChanged(List<WebExtension> extensions);
    }

    /** Fired once the scroll-reporter extension is installed. */
    public interface ScrollExtensionListener {
        void onScrollExtensionReady(WebExtension extension);
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

        if (mScrollExtension == null) {
            ScrollExtension.install(mController, extension -> {
                mScrollExtension = extension;
                Log.i(TAG, "scroll extension ready: " + extension.id);
                if (mScrollListener != null) {
                    mScrollListener.onScrollExtensionReady(extension);
                }
            });
        }

        refresh();
    }

    /**
     * Registers a listener that fires when the scroll reporter is ready.
     * If the extension is already installed, the listener fires immediately.
     */
    public void setScrollExtensionListener(@Nullable ScrollExtensionListener listener) {
        mScrollListener = listener;
        if (listener != null && mScrollExtension != null) {
            listener.onScrollExtensionReady(mScrollExtension);
        }
    }

    @Nullable
    public WebExtension getScrollExtension() {
        return mScrollExtension;
    }

    // ... rest of the class unchanged (setListener, refresh,
    //     installFromUrl, uninstall, enable, disable) ...
}
