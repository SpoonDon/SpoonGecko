package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt;

import java.util.ArrayList;
import java.util.List;

/**
 * [H1] GeckoSession.PromptDelegate implementation.
 *
 * The browser previously installed no PromptDelegate at all, so every
 * content-initiated prompt failed silently:
 *   - alert() / confirm() / prompt()           -> JS blocked forever
 *   - <select> menus                           -> unusable
 *   - <input type=file>                        -> no picker
 *   - HTTP basic/proxy auth                    -> cancelled
 *   - navigator.share(), beforeunload, POST-resubmit, popup blocking -> nothing
 *
 * All callbacks are invoked by GeckoView on the UI thread, so dialogs can be
 * shown directly. Every result is completed exactly once (BasePrompt throws
 * "Cannot confirm/dismiss a Prompt twice" otherwise).
 *
 * [UPLOAD] File inputs are handled by {@link FilePicker}.
 */
public final class BrowserPrompts implements GeckoSession.PromptDelegate {

    private final Activity activity;
    private final List<AlertDialog> activeDialogs = new ArrayList<>();   // [M8]

    // [UPLOAD] <input type=file>: the picker, the copying and the hand-over to the page.
    private final FilePicker filePicker;

    public BrowserPrompts(Activity activity) {
        this.activity = activity;
        this.filePicker = new FilePicker(activity);
    }

    /** Lets a GeckoResult be completed exactly once. */
    private static final class Once {
        private boolean done;
        boolean fire() {
            if (done) return false;
            done = true;
            return true;
        }
    }

    private boolean cannotShowDialogs() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    private AlertDialog track(AlertDialog d) {
        activeDialogs.add(d);
        d.setOnDismissListener(x -> activeDialogs.remove(d));
        return d;
    }

    /** [UPLOAD] Notifies FilePicker when the activity returns to the foreground. */
    public void onResume() {
        filePicker.onResume();
    }

    /** [UPLOAD] Notifies FilePicker when the activity pauses (e.g. while an external picker is open). */
    public void onPause() {
        filePicker.onPause();
    }

    /** [M8] Called from MainActivity.onDestroy() so no dialog window leaks. */
    public void dismissAll() {
        for (AlertDialog d : new ArrayList<>(activeDialogs)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {
            }
        }
        activeDialogs.clear();
        filePicker.dismissPending();   // [UPLOAD]
    }

    private int dp(int px) {
        return Math.round(px * activity.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // alert()
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(
            GeckoSession session, final AlertPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs()) {
            result.complete(prompt.dismiss());
            return result;
        }
        final Once once = new Once();
        // AlertPrompt can only be dismissed, never confirmed (per API docs).
        track(new AlertDialog.Builder(activity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .setOnCancelListener(
                        d -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .show());
        return result;
    }

    // ------------------------------------------------------------------
    // confirm()
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(
            GeckoSession session, final ButtonPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs()) {
            result.complete(prompt.dismiss());
            return result;
        }
        final Once once = new Once();
        track(new AlertDialog.Builder(activity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE));
                })
                .setOnCancelListener(
                        d -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .show());
        return result;
    }

    // ------------------------------------------------------------------
    // prompt()
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onTextPrompt(
            GeckoSession session, final TextPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs()) {
            result.complete(prompt.dismiss());
            return result;
        }
        final Once once = new Once();
        final EditText input = new EditText(activity);
        input.setText(prompt.defaultValue == null ? "" : prompt.defaultValue);
        track(new AlertDialog.Builder(activity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(input.getText().toString()));
                })
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .setOnCancelListener(
                        d -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .show());
        return result;
    }

    // ------------------------------------------------------------------
    // HTTP basic / proxy authentication
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(
            GeckoSession session, final AuthPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs()) {
            result.complete(prompt.dismiss());
            return result;
        }
        final Once once = new Once();
        final boolean passwordOnly =
                (prompt.authOptions.flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;

        final EditText user = new EditText(activity);
        user.setHint("Username");
        user.setText(prompt.authOptions.username == null ? ""
                : prompt.authOptions.username);

        final EditText pass = new EditText(activity);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(prompt.authOptions.password == null ? ""
                : prompt.authOptions.password);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(4), pad, 0);
        if (!passwordOnly) {
            box.addView(user, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        box.addView(pass, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        track(new AlertDialog.Builder(activity)
                .setTitle(prompt.title != null ? prompt.title : "Sign in")
                .setMessage(prompt.message)
                .setView(box)
                .setPositiveButton("Sign in", (d, w) -> {
                    if (!once.fire()) return;
                    if (passwordOnly) {
                        result.complete(prompt.confirm(pass.getText().toString()));
                    } else {
                        result.complete(prompt.confirm(
                                user.getText().toString(), pass.getText().toString()));
                    }
                })
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .setOnCancelListener(
                        d -> { if (once.fire()) result.complete(prompt.dismiss()); })
                .show());
        return result;
    }

    // ------------------------------------------------------------------
    // <select> menus
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(
            GeckoSession session, final ChoicePrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs() || prompt.choices == null || prompt.choices.length == 0) {
            result.complete(prompt.dismiss());
            return result;
        }

        // Flatten <optgroup> groups (Choice.items) into a single list; children
        // are prefixed with the group label.
        final List<ChoicePrompt.Choice> flat = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (ChoicePrompt.Choice c : prompt.choices) {
            if (c.items != null) {
                for (ChoicePrompt.Choice child : c.items) {
                    flat.add(child);
                    labels.add((c.label == null || c.label.isEmpty() ? ""
                            : c.label + " \u2014 ") + child.label);
                }
            } else {
                flat.add(c);
                labels.add(c.label);
            }
        }

        final Once once = new Once();
        final CharSequence[] items = labels.toArray(new CharSequence[0]);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(prompt.title != null ? prompt.title : prompt.message);

        if (prompt.type == ChoicePrompt.Type.MENU) {
            // Dropdown: pick = confirm immediately.
            builder.setItems(items, (d, which) -> {
                if (once.fire()) result.complete(prompt.confirm(flat.get(which).id));
            });
        } else if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            final boolean[] checks = new boolean[flat.size()];
            for (int i = 0; i < flat.size(); i++) checks[i] = flat.get(i).selected;
            builder.setMultiChoiceItems(items, checks,
                    (d, which, isChecked) -> checks[which] = isChecked);
            builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                if (!once.fire()) return;
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < flat.size(); i++) {
                    if (checks[i]) ids.add(flat.get(i).id);
                }
                result.complete(prompt.confirm(ids.toArray(new String[0])));
            });
        } else {   // ChoicePrompt.Type.SINGLE
            int checked = -1;
            for (int i = 0; i < flat.size(); i++) {
                if (flat.get(i).selected) { checked = i; break; }
            }
            final int[] selected = {checked};
            builder.setSingleChoiceItems(items, checked,
                    (d, which) -> selected[0] = which);
            builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                if (!once.fire()) return;
                if (selected[0] >= 0) result.complete(prompt.confirm(flat.get(selected[0]).id));
                else result.complete(prompt.dismiss());
            });
        }

        builder.setNegativeButton(android.R.string.cancel,
                (d, w) -> { if (once.fire()) result.complete(prompt.dismiss()); });
        builder.setOnCancelListener(
                d -> { if (once.fire()) result.complete(prompt.dismiss()); });
        track(builder.show());
        return result;
    }

    // ------------------------------------------------------------------
    // <input type=file>
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onFilePrompt(
            GeckoSession session, final FilePrompt prompt) {
        return filePicker.onFilePrompt(prompt);   // [UPLOAD]
    }

    /**
     * [H1] Called from MainActivity.onActivityResult(); returns true when the
     * result belongs to the file picker.
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return filePicker.handleActivityResult(requestCode, resultCode, data);   // [UPLOAD]
    }

    /**
     * [UPLOAD] Called from MainActivity.onRequestPermissionsResult(); returns true when the
     * permission request belongs to FilePicker's capture flow.
     */
    public boolean handleRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        return filePicker.handleRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ------------------------------------------------------------------
    // Popup blocking (window.open without a user gesture)
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(
            GeckoSession session, final PopupPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        result.complete(prompt.confirm(AllowOrDeny.DENY));
        return result;
    }

    // ------------------------------------------------------------------
    // onbeforeunload ("Leave site? Changes may not be saved.")
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(
            GeckoSession session, final BeforeUnloadPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs()) {
            result.complete(prompt.confirm(AllowOrDeny.DENY));
            return result;
        }
        final Once once = new Once();
        track(new AlertDialog.Builder(activity)
                .setTitle("Leave page?")
                .setMessage("Changes you made may not be saved.")
                .setPositiveButton("Leave", (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(AllowOrDeny.ALLOW));
                })
                .setNegativeButton("Stay", (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(AllowOrDeny.DENY));
                })
                .setOnCancelListener(d -> {
                    if (once.fire()) result.complete(prompt.confirm(AllowOrDeny.DENY));
                })
                .show());
        return result;
    }

    // ------------------------------------------------------------------
    // POST data resubmission ("Send data again?")
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(
            GeckoSession session, final RepostConfirmPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (cannotShowDialogs()) {
            result.complete(prompt.confirm(AllowOrDeny.DENY));
            return result;
        }
        final Once once = new Once();
        track(new AlertDialog.Builder(activity)
                .setTitle("Confirm form submission")
                .setMessage("This page needs to resend the data you previously entered. Continue?")
                .setPositiveButton("Send", (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(AllowOrDeny.ALLOW));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (once.fire()) result.complete(prompt.confirm(AllowOrDeny.DENY));
                })
                .setOnCancelListener(d -> {
                    if (once.fire()) result.complete(prompt.confirm(AllowOrDeny.DENY));
                })
                .show());
        return result;
    }

    // ------------------------------------------------------------------
    // navigator.share() -> Android share sheet
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onSharePrompt(
            GeckoSession session, final SharePrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        StringBuilder sb = new StringBuilder();
        if (prompt.text != null) sb.append(prompt.text);
        if (prompt.uri != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(prompt.uri);
        }
        if (sb.length() == 0) {
            result.complete(prompt.dismiss());
            return result;
        }
        // [F4] shareText() used to be able to crash (ActivityNotFoundException on
        // devices with no share target). It now reports success/failure, and the
        // failure is passed back to the page instead of throwing.
        boolean shown = PopupsController.shareText(activity, sb.toString());
        result.complete(prompt.confirm(shown ? SharePrompt.Result.SUCCESS
                                             : SharePrompt.Result.FAILURE));
        return result;
    }

    // ------------------------------------------------------------------
    // <input type=color> / datetime — dismissed (input cancelled).
    // A native color/date picker can be added here later if needed.
    // ------------------------------------------------------------------
    @Override
    public GeckoResult<PromptResponse> onColorPrompt(
            GeckoSession session, final ColorPrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        result.complete(prompt.dismiss());
        return result;
    }

    @Override
    public GeckoResult<PromptResponse> onDateTimePrompt(
            GeckoSession session, final DateTimePrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        result.complete(prompt.dismiss());
        return result;
    }
}
