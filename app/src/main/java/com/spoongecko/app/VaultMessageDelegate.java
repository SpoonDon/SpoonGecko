package com.spoongecko.app;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;

final class VaultMessageDelegate implements WebExtension.MessageDelegate {

    private final Activity activity;

    VaultMessageDelegate(Activity activity) {
        this.activity = activity;
    }

    public GeckoResult<Object> onMessage(Object message, WebExtension.MessageSender sender) {
        if (!(message instanceof JSONObject)) return null;
        JSONObject json = (JSONObject) message;
        String action = json.optString("action", "");
        if (!"AUTOSAVE_PROMPT".equals(action)) return null;

        String host = json.optString("host", "").trim();
        String username = json.optString("username", "").trim();
        String password = json.optString("password", "");
        if (host.isEmpty() || password.isEmpty()) return null;

        Activity target = activity;
        if (target == null || target.isFinishing() || target.isDestroyed()) return null;

        target.runOnUiThread(() -> prompt(target, host, username, password));
        return null;
    }

    private void prompt(Activity target, String host, String username, String password) {
        SecureCredentialManager.get(target).hasCredential(host, username, password, same -> {
            target.runOnUiThread(() -> {
                if (same) return;
                new AlertDialog.Builder(target)
                        .setTitle(R.string.vault_prompt_title)
                        .setMessage(target.getString(R.string.vault_prompt_message, host, username))
                        .setPositiveButton(R.string.vault_prompt_save, (dialog, which) ->
                                SecureCredentialManager.get(target)
                                        .saveCredentials(host, username, password))
                        .setNegativeButton(R.string.vault_prompt_not_now, null)
                        .show();
            });
        });
    }
}
