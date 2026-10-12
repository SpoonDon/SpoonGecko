package com.spoongecko.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * PromptDelegate for GeckoSession. Currently handles only HTTP Basic Auth
 * (onAuthPrompt), which is the prompt that routers, ISP modems, corporate
 * proxies and self-hosted admin panels rely on.
 *
 * Without a PromptDelegate, GeckoView cancels the auth prompt and renders a
 * blank white page: the request reached the server, the server replied
 * 401 Unauthorized, and the browser has nowhere to show the credential
 * fields. Setting this delegate on every new session fixes it.
 *
 * API reference (GeckoView 157):
 *   onAuthPrompt(session, prompt)
 *   prompt.message          -> realm / message text (nullable)
 *   prompt.title            -> optional title (nullable, inherited)
 *   prompt.authOptions.uri  -> request URI (nullable)
 *   prompt.authOptions.username -> prefill, or null if password-only
 *   prompt.authOptions.password -> prefill (nullable)
 *   prompt.confirm(password)                 -> PromptResponse
 *   prompt.confirm(username, password)       -> PromptResponse
 *   prompt.dismiss()                         -> PromptResponse
 *
 * Other prompt types (alerts, file pickers, geolocation, etc.) fall through
 * to GeckoView's default dismissal, matching pre-existing behaviour. They
 * can be added incrementally on top of this class.
 */
public class AuthPromptHandler implements PromptDelegate {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> seenRealms = new HashSet<>();

    public AuthPromptHandler(Context context) {
        this.context = context;
    }

    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session,
                                                    AuthPrompt prompt) {
        if (prompt == null) return null;
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        mainHandler.post(() -> showDialog(prompt, result));
        return result;
    }

    // ------------------------------------------------------------------ dialog

    private void showDialog(AuthPrompt prompt,
                            GeckoResult<PromptResponse> result) {
        try {
            final AuthPrompt.AuthOptions opts = prompt.authOptions;
            final String realm = prompt.message == null ? "" : prompt.message;
            final String uriStr = opts == null ? null : opts.uri;
            final String prefillUser = opts == null ? null : opts.username;
            final String prefillPass = opts == null ? null : opts.password;
            final boolean passwordOnly = prefillUser == null;

            // If we have seen this realm before, the previous attempt failed.
            // GeckoView does not expose a "previous failed" flag in 157.
            final boolean retry = !realm.isEmpty() && !seenRealms.add(realm);

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
            if (!passwordOnly) {
                userField = new EditText(context);
                userField.setHint("Username");
                userField.setSingleLine(true);
                userField.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                if (prefillUser != null && !prefillUser.isEmpty()) {
                    userField.setText(prefillUser);
                }
                container.addView(userField);
            } else {
                userField = null;
            }

            EditText passField = new EditText(context);
            passField.setHint("Password");
            passField.setSingleLine(true);
            passField.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if (prefillPass != null && !prefillPass.isEmpty()) {
                passField.setText(prefillPass);
            }
            container.addView(passField);

            String title = retry ? "Sign in (previous attempt failed)" : "Sign in";
            String host = hostOf(uriStr);

            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(title);
            if (!host.isEmpty()) b.setMessage(host);
            b.setView(container);

            final EditText userFinal = userField;
            final EditText passFinal = passField;
            b.setPositiveButton("Sign in", (d, w) -> {
                String user = userFinal == null ? "" : userFinal.getText().toString();
                String pass = passFinal.getText().toString();
                PromptResponse response;
                if (userFinal == null) {
                    response = prompt.confirm(pass);
                } else {
                    response = prompt.confirm(user, pass);
                }
                result.complete(response);
            });
            b.setNegativeButton("Cancel", (d, w) ->
                    result.complete(prompt.dismiss()));
            b.setOnCancelListener(d ->
                    result.complete(prompt.dismiss()));
            b.show();
        } catch (Throwable t) {
            try {
                result.complete(prompt.dismiss());
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
