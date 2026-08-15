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
        return prompt(extension, null, null, null);
    }

    public GeckoResult<AllowOrDeny> onInstallPromptRequest(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollectionPermissions) {
        return prompt(extension, permissions, origins, dataCollectionPermissions);
    }

    private GeckoResult<AllowOrDeny> prompt(
            WebExtension extension,
            String[] permissions,
            String[] origins,
            String[] dataCollectionPermissions) {
        GeckoResult<AllowOrDeny> result = new GeckoResult<>();
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            result.complete(AllowOrDeny.DENY);
            return result;
        }

        String name = (extension != null && extension.metaData != null
                && extension.metaData.name != null && !extension.metaData.name.isEmpty())
                ? extension.metaData.name
                : (extension != null && extension.id != null ? extension.id : "this extension");

        String details = buildDetails(permissions, origins, dataCollectionPermissions);
        String message = activity.getString(R.string.install_extension_message, name);
        if (!details.isEmpty()) {
            message = message + "\n\n" + details;
        }
        String finalMessage = message;

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                result.complete(AllowOrDeny.DENY);
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.install_extension_title)
                    .setMessage(finalMessage)
                    .setPositiveButton(R.string.install, (d, w) -> result.complete(AllowOrDeny.ALLOW))
                    .setNegativeButton(R.string.cancel, (d, w) -> result.complete(AllowOrDeny.DENY))
                    .setOnCancelListener(d -> result.complete(AllowOrDeny.DENY))
                    .show();
        });
        return result;
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
