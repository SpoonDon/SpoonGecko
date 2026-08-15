package com.spoongecko.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class InstallPromptDelegate implements WebExtensionController.PromptDelegate {

    private final WeakReference<Activity> activityRef;

    public InstallPromptDelegate(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    public GeckoResult<AllowOrDeny> onInstallPrompt(WebExtension extension) {
        GeckoResult<AllowOrDeny> result = new GeckoResult<>();
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            result.complete(AllowOrDeny.DENY);
            return result;
        }
        String message = activity.getString(R.string.install_extension_message, displayName(extension));
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                result.complete(AllowOrDeny.DENY);
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.install_extension_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.install, (d, w) -> result.complete(AllowOrDeny.ALLOW))
                    .setNegativeButton(R.string.cancel, (d, w) -> result.complete(AllowOrDeny.DENY))
                    .setOnCancelListener(d -> result.complete(AllowOrDeny.DENY))
                    .show();
        });
        return result;
    }

    public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollectionPermissions) {
        GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            result.complete(deny());
            return result;
        }

        String message = activity.getString(R.string.install_extension_message, displayName(extension));
        String details = buildDetails(permissions, origins, dataCollectionPermissions);
        if (!details.isEmpty()) {
            message = message + "\n\n" + details;
        }
        String finalMessage = message;

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                result.complete(deny());
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.install_extension_title)
                    .setMessage(finalMessage)
                    .setPositiveButton(R.string.install, (d, w) -> result.complete(allow()))
                    .setNegativeButton(R.string.cancel, (d, w) -> result.complete(deny()))
                    .setOnCancelListener(d -> result.complete(deny()))
                    .show();
        });
        return result;
    }

    private static WebExtension.PermissionPromptResponse allow() {
        return new WebExtension.PermissionPromptResponse(true, true, true);
    }

    private static WebExtension.PermissionPromptResponse deny() {
        return new WebExtension.PermissionPromptResponse(false, false, false);
    }

    private String displayName(WebExtension extension) {
        if (extension != null && extension.metaData != null
                && extension.metaData.name != null && !extension.metaData.name.isEmpty()) {
            return extension.metaData.name;
        }
        return extension != null && extension.id != null ? extension.id : "this extension";
    }

    private String buildDetails(String[] permissions, String[] origins,
                                String[] dataCollectionPermissions) {
        List<String> lines = new ArrayList<>();
        if (permissions != null && permissions.length > 0) {
            lines.add("Permissions:\n" + TextUtils.join("\n", permissions));
        }
        if (origins != null && origins.length > 0) {
            lines.add("Site access:\n" + TextUtils.join("\n", origins));
        }
        if (dataCollectionPermissions != null && dataCollectionPermissions.length > 0) {
            lines.add("Data collection:\n" + TextUtils.join("\n", dataCollectionPermissions));
        }
        if (lines.isEmpty()) return "";
        return TextUtils.join("\n\n", lines);
    }
}
