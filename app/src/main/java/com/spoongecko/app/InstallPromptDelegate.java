package com.spoongecko.app;

import android.app.Activity;
import android.app.AlertDialog;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.lang.ref.WeakReference;

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
        String name = (extension.metaData != null && extension.metaData.name != null
                && !extension.metaData.name.isEmpty())
                ? extension.metaData.name
                : (extension.id != null ? extension.id : "this extension");
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                result.complete(AllowOrDeny.DENY);
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.install_extension_title)
                    .setMessage(activity.getString(R.string.install_extension_message, name))
                    .setPositiveButton(R.string.install, (d, w) -> result.complete(AllowOrDeny.ALLOW))
                    .setNegativeButton(R.string.cancel, (d, w) -> result.complete(AllowOrDeny.DENY))
                    .setOnCancelListener(d -> result.complete(AllowOrDeny.DENY))
                    .show();
        });
        return result;
    }
}
