package com.spoongecko.browser;

import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

/**
 * Refreshes the extension list whenever GeckoView reports a lifecycle change.
 * All methods are {@code @UiThread} per the GeckoView 157 Javadoc.
 */
public class ExtensionAddonManagerDelegate implements WebExtensionController.AddonManagerDelegate {

    private final ExtensionManager mManager;

    public ExtensionAddonManagerDelegate(ExtensionManager manager) {
        mManager = manager;
    }

    @Override
    public void onInstalled(WebExtension extension) {
        mManager.refresh();
    }

    @Override
    public void onUninstalled(WebExtension extension) {
        mManager.refresh();
    }

    @Override
    public void onEnabled(WebExtension extension) {
        mManager.refresh();
    }

    @Override
    public void onDisabled(WebExtension extension) {
        mManager.refresh();
    }

    @Override
    public void onInstallationFailed(WebExtension extension, WebExtension.InstallException exception) {
        // The GeckoResult in ExtensionManager already logs the failure.
        // This hook exists so the UI could show a toast if desired.
    }
}
