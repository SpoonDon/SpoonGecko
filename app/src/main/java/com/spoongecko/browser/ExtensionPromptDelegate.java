package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.lang.ref.WeakReference;

/**
 * Handles the install-permission dialog for WebExtensions.
 *
 * onInstallPrompt was deprecated in GeckoView 133 and deleted in 134.
 * This uses the current onInstallPromptRequest returning a
 * WebExtension.PermissionPromptResponse.
 */
public class ExtensionPromptDelegate implements WebExtensionController.PromptDelegate {

    private static final String TAG = "ExtPromptDelegate";

    private final WeakReference<Activity> mActivityRef;

    public ExtensionPromptDelegate(Activity activity) {
        mActivityRef = new WeakReference<>(activity);
    }

    @Override
    public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollectionPermissions) {

        final GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
        final Activity activity = mActivityRef.get();

        if (activity == null || activity.isFinishing()) {
            result.complete(new WebExtension.PermissionPromptResponse(false, false, false));
            return result;
        }

        final String name = (extension != null && extension.metaData != null
                && extension.metaData.name != null)
                ? extension.metaData.name : "this extension";

        StringBuilder msg = new StringBuilder();
        msg.append("Install \"").append(name).append("\"?\n");
        if (permissions != null && permissions.length > 0) {
            msg.append("\nPermissions:\n");
            for (String p : permissions) msg.append("• ").append(p).append('\n');
        }
        if (origins != null && origins.length > 0) {
            msg.append("\nAccess to:\n");
            for (String o : origins) msg.append("• ").append(o).append('\n');
        }

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("Add extension")
                        .setMessage(msg.toString().trim())
                        .setPositiveButton("Allow", (d, w) ->
                                result.complete(new WebExtension.PermissionPromptResponse(
                                        true, false, false)))
                        .setNegativeButton("Deny", (d, w) ->
                                result.complete(new WebExtension.PermissionPromptResponse(
                                        false, false, false)))
                        .setOnCancelListener(d ->
                                result.complete(new WebExtension.PermissionPromptResponse(
                                        false, false, false)))
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "Dialog failed", e);
                result.complete(new WebExtension.PermissionPromptResponse(
                        false, false, false));
            }
        });

        return result;
    }
}
