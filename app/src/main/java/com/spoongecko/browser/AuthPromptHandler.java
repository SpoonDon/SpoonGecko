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

/**
 * PromptDelegate implementation modelled directly on Mozilla's own
 * BasicGeckoViewPrompt.java from the geckoview_example app.
 *
 * Without this delegate, GeckoView auto-dismisses every auth challenge
 * (HTTP 401) and the page renders blank. Routers, ISP modems and
 * corporate proxies all use HTTP Basic Auth, so this is not optional.
 */
public class AuthPromptHandler implements PromptDelegate {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    private void showDialog(AuthPrompt prompt,
                            GeckoResult<PromptResponse> result) {
        try {
            final AuthPrompt.AuthOptions opts = prompt.authOptions;
            final int flags = (opts == null) ? 0 : opts.flags;
            final boolean onlyPassword =
                    (flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;
            final String prefillUser = (opts == null) ? null : opts.username;
            final String prefillPass = (opts == null) ? null : opts.password;

            int pad = dp(20);
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(pad, dp(4), pad, 0);

            if (prompt.message != null && !prompt.message.isEmpty()) {
                TextView msg = new TextView(context);
                msg.setText(prompt.message);
                msg.setTextSize(13f);
                msg.setAlpha(0.7f);
                container.addView(msg);
            }

            final EditText userField;
            if (!onlyPassword) {
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

            String title = (prompt.title == null || prompt.title.isEmpty())
                    ? "Sign in" : prompt.title;

            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(title);
            b.setView(container);

            final EditText userFinal = userField;
            final EditText passFinal = passField;
            b.setPositiveButton(android.R.string.ok, (d, w) -> {
                String pass = passFinal.getText().toString();
                PromptResponse response;
                if (userFinal == null) {
                    response = prompt.confirm(pass);
                } else {
                    response = prompt.confirm(userFinal.getText().toString(), pass);
                }
                result.complete(response);
            });
            b.setNegativeButton(android.R.string.cancel, (d, w) ->
                    result.complete(prompt.dismiss()));
            b.setOnCancelListener(d ->
                    result.complete(prompt.dismiss()));
            b.show();
        } catch (Throwable t) {
            try { result.complete(prompt.dismiss()); } catch (Throwable ignored) {}
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources()
                .getDisplayMetrics().density);
    }
}
