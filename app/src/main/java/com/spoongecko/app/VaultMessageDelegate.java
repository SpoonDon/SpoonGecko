package com.spoongecko.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;

final class VaultMessageDelegate implements WebExtension.MessageDelegate {

    private static void beacon(String text) {
        Context ctx = VaultSessionBinder.appContext();
        if (ctx == null) return;
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "[vault] " + text, Toast.LENGTH_LONG).show());
    }

    public GeckoResult<Object> onMessage(Object message, WebExtension.MessageSender sender) {
        if (!(message instanceof JSONObject)) {
            beacon("onMessage: non-JSON payload");
            return GeckoResult.fromValue(null);
        }

        JSONObject json = (JSONObject) message;
        String action = json.optString("action", "");
        beacon("onMessage: " + action);

        if ("DEBUG_CONTENT_LOADED".equals(action)) {
            return GeckoResult.fromValue(null);
        }
        if ("DEBUG_FORM_DETECTED".equals(action)) {
            boolean hasPassword = json.optBoolean("hasPassword", false);
            String url = json.optString("url", "");
            beacon("form detected: hasPassword=" + hasPassword + " url=" + url);
            return GeckoResult.fromValue(null);
        }
        if (!"AUTOSAVE_PROMPT".equals(action)) {
            return GeckoResult.fromValue(null);
        }

        String host = json.optString("host", "").trim();
        String url = json.optString("url", "").trim();
        String username = json.optString("username", "").trim();
        String password = json.optString("password", "");
        if (host.isEmpty() || password.isEmpty()) {
            beacon("AUTOSAVE_PROMPT missing host or password");
            return GeckoResult.fromValue(null);
        }

        Activity target = VaultSessionBinder.currentActivity();
        if (target == null || target.isFinishing() || target.isDestroyed()) {
            beacon("no target activity, dropped");
            return GeckoResult.fromValue(null);
        }

        final String sourceUrl = url;
        target.runOnUiThread(() -> prompt(target, host, sourceUrl, username, password));
        return GeckoResult.fromValue(null);
    }

    private void prompt(Activity target, String host, String sourceUrl,
                        String username, String password) {
        SecureCredentialManager.get(target).hasCredential(host, username, password, same -> {
            target.runOnUiThread(() -> {
                beacon("hasCredential=" + same + " host=" + host);
                if (same) return;
                new AlertDialog.Builder(target)
                        .setTitle(R.string.vault_prompt_title)
                        .setMessage(target.getString(R.string.vault_prompt_message, host, username))
                        .setPositiveButton(R.string.vault_prompt_save, (dialog, which) -> {
                            SecureCredentialManager.get(target)
                                    .saveCredentialsWithUrl(host, sourceUrl, username, password);
                            beacon("saved: " + host);
                        })
                        .setNegativeButton(R.string.vault_prompt_not_now, null)
                        .show();
            });
        });
    }
}
