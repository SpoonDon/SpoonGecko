package com.spoongecko.browser;

import android.content.Context;
import android.widget.Toast;

import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

/**
 * Refreshes the extension list whenever GeckoView reports a lifecycle
 * change. All methods are {@code @UiThread} per GeckoView 157 Javadoc.
 */
public class ExtensionAddonManagerDelegate
        implements WebExtensionController.AddonManagerDelegate {

    private final ExtensionManager mManager;
    private final Context mAppContext;

    public ExtensionAddonManagerDelegate(ExtensionManager manager) {
        mManager = manager;
        // GeckoView instantiates this from the manager, which has no
        // Context. Use the process-wide app context captured at install
        // time via GeckoApp.
        mAppContext = GeckoApp.getAppContext();
    }

    @Override public void onInstalled(WebExtension extension)  { mManager.refresh(); }
    @Override public void onUninstalled(WebExtension extension){ mManager.refresh(); }
    @Override public void onEnabled(WebExtension extension)    { mManager.refresh(); }
    @Override public void onDisabled(WebExtension extension)   { mManager.refresh(); }

    @Override
    public void onInstallationFailed(WebExtension extension,
                                     WebExtension.InstallException exception) {
        String name = (extension != null && extension.metaData != null
                && extension.metaData.name != null)
                ? extension.metaData.name : "Extension";
        String reason = (exception != null)
                ? exception.getClass().getSimpleName() : "unknown error";
        if (mAppContext != null) {
            Toast.makeText(mAppContext,
                    "Failed to install " + name + " (" + reason + ")",
                    Toast.LENGTH_LONG).show();
        }
        mManager.refresh();
    }
}
