package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
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
 */
public final class BrowserPrompts implements GeckoSession.PromptDelegate {

    private static final int REQ_FILE_PICKER = 0x7111;

    private final Activity activity;
    private final List<AlertDialog> activeDialogs = new ArrayList<>();   // [M8]

    // One file picker at a time; results arrive via Activity.onActivityResult.
    private FilePrompt pendingFilePrompt;
    private GeckoResult<PromptResponse> pendingFileResult;

    public BrowserPrompts(Activity activity) {
        this.activity = activity;
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

    /** [M8] Called from MainActivity.onDestroy() so no dialog window leaks. */
    public void dismissAll() {
        for (AlertDialog d : new ArrayList<>(activeDialogs)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {
            }
        }
        activeDialogs.clear();
        if (pendingFilePrompt != null && pendingFileResult != null) {
            try {
                pendingFileResult.complete(pendingFilePrompt.dismiss());
            } catch (Exception ignored) {
            }
            pendingFilePrompt = null;
            pendingFileResult = null;
        }
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
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        if (prompt.type == FilePrompt.Type.FOLDER) {
            // Directory uploads need ACTION_OPEN_DOCUMENT_TREE; not needed by
            // the vast majority of content — dismiss for now.
            result.complete(prompt.dismiss());
            return result;
        }
        if (pendingFilePrompt != null) {
            // Only one picker at a time; dismiss the newer request.
            result.complete(prompt.dismiss());
            return result;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (prompt.mimeTypes != null && prompt.mimeTypes.length > 0) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
        }
        if (prompt.type == FilePrompt.Type.MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        try {
            activity.startActivityForResult(intent, REQ_FILE_PICKER);
            pendingFilePrompt = prompt;
            pendingFileResult = result;
        } catch (Exception e) {
            result.complete(prompt.dismiss());
        }
        return result;
    }

    /**
     * [H1] Called from MainActivity.onActivityResult(); returns true when the
     * result belongs to the file picker.
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_PICKER) return false;

        final FilePrompt prompt = pendingFilePrompt;
        final GeckoResult<PromptResponse> result = pendingFileResult;
        pendingFilePrompt = null;
        pendingFileResult = null;
        if (prompt == null || result == null) return true;

        final List<Uri> uris = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getData() != null) uris.add(data.getData());
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null && !uris.contains(u)) uris.add(u);
                }
            }
        }

        // FilePrompt.confirm() requires exactly one URI for single-file inputs.
        if (uris.isEmpty()
                || (prompt.type != FilePrompt.Type.MULTIPLE && uris.size() != 1)) {
            result.complete(prompt.dismiss());
        } else {
            result.complete(prompt.confirm(activity, uris.toArray(new Uri[0])));
        }
        return true;
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
        PopupsController.shareText(activity, sb.toString());
        result.complete(prompt.confirm(SharePrompt.Result.SUCCESS));
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
