package com.spoongecko.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.AuthPrompt;
import org.mozilla.geckoview.AuthPromptResponse;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.PromptResponse;

import java.net.URI;

/**
 * PromptDelegate for GeckoSession. Currently handles only HTTP Basic Auth
 * (onAuthPrompt), which is the single prompt that routers, ISP modems,
 * corporate proxies and self-hosted admin panels rely on.
 *
 * Without a PromptDelegate, GeckoView cancels the auth prompt and renders
 * a blank white page: the request succeeded at TCP level, the server
 * replied 401 Unauthorized, and the browser has nowhere to show the
 * credential fields. Setting this delegate on every new session fixes it.
 *
 * Other prompt types (permissions, file picker, geolocation, notifications)
 * fall through to GeckoView's default dismissal, which matches the
 * behaviour before this class existed. They can be added incrementally.
 */
public class AuthPromptHandler implements GeckoSession.PromptDelegate {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AuthPromptHandler(Context context) {
        this.context = context;
    }

    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session,
                                                    String uri,
                                                    AuthPrompt prompt) {
        if (prompt == null) {
            return GeckoResult.fromValue(PromptResponse.createDismiss());
        }
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        mainHandler.post(() -> showDialog(uri, prompt, result));
        return result;
    }

    // ------------------------------------------------------------------ dialog

    private void showDialog(String uri, AuthPrompt prompt,
                            GeckoResult<PromptResponse> result) {
        try {
            final String realm = prompt.realm == null ? "" : prompt.realm;
            final boolean onlyPassword = prompt.onlyPassword;
            final boolean retry = prompt.previousFailed;

            int pad = dp(20);
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(pad, dp(4), pad, 0);

            if (!realm.isEmpty()) {
                TextView realmView = new TextView(context);
                realmView.setText(realm);
                realmView.setTextSize(13f);
                realmView.setAlpha(0.7f);
                container.addView(realmView);
            }

            final EditText userField;
            if (!onlyPassword) {
                userField = new EditText(context);
                userField.setHint("Username");
                userField.setSingleLine(true);
                userField.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                container.addView(userField);
            } else {
                userField = null;
            }

            EditText passField = new EditText(context);
            passField.setHint("Password");
            passField.setSingleLine(true);
            passField.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            container.addView(passField);

            String title = retry ? "Sign in (previous attempt failed)" : "Sign in";
            String host = hostOf(uri);

            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(title);
            if (!host.isEmpty()) b.setMessage(host);
            b.setView(container);

            final EditText userFinal = userField;
            final EditText passFinal = passField;
            b.setPositiveButton("Sign in", (d, w) -> {
                String user = userFinal == null ? "" : userFinal.getText().toString();
                String pass = passFinal.getText().toString();
                result.complete(AuthPromptResponse.create(prompt, user, pass));
            });
            b.setNegativeButton("Cancel", (d, w) ->
                    result.complete(PromptResponse.createDismiss()));
            b.setOnCancelListener(d ->
                    result.complete(PromptResponse.createDismiss()));
            b.show();
        } catch (Throwable t) {
            try {
                result.complete(PromptResponse.createDismiss());
            } catch (Throwable ignored) {}
        }
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources()
                .getDisplayMetrics().density);
    }
}
