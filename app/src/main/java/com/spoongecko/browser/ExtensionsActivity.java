package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.WebExtension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Add-ons manager" screen: list installed extensions with on/off
 * switches, install new ones (URL / quick add / local .xpi), update them,
 * back up and restore the installed set, and toggle automatic updates.
 *
 * [M8] Every dialog shown here is tracked and dismissed in bulk from
 * onDestroy(), so none can outlive the activity and crash with
 * "android.view.WindowLeaked" (same pattern as BrowserDialogs).
 */
public class ExtensionsActivity extends Activity {

    private static final String TAG = "SpoonGecko";

    /** Live-checked: redirects to the current signed uBlock Origin xpi. */
    private static final String UBLOCK_URL =
            "https://addons.mozilla.org/android/downloads/latest/ublock-origin/addon-607454-latest.xpi";
    private static final String AMO_ANDROID = "https://addons.mozilla.org/android/";

    private static final int REQ_BACKUP = 4101;
    private static final int REQ_RESTORE = 4102;
    private static final int REQ_INSTALL_FILE = 4103;

    private Adapter adapter;
    private TextView autoButton;
    private final List<Dialog> activeDialogs = new ArrayList<>();   // [M8]

    private final ExtensionManager.Listener listener = this::onExtensionsChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extensions);

        final ExtensionManager em = ExtensionManager.get();
        em.initialize(this);
        em.addListener(listener);

        ImageButton back = findViewById(R.id.btn_ext_back);
        back.setOnClickListener(v -> finish());

        ImageButton add = findViewById(R.id.btn_ext_add);
        add.setOnClickListener(v -> showInstallDialog());

        TextView check = findViewById(R.id.btn_ext_check);
        check.setOnClickListener(v -> {
            toast("Checking for updates…");
            em.checkForUpdatesAll((updated, failed, checked) -> {
                if (updated > 0) toast(updated + " of " + checked + " extension(s) updated");
                else if (failed > 0) toast("Updates failed for " + failed + " extension(s)");
                else toast("All extensions are up to date");
            });
        });

        TextView backup = findViewById(R.id.btn_ext_backup);
        backup.setOnClickListener(v -> startBackup());

        TextView restore = findViewById(R.id.btn_ext_restore);
        restore.setOnClickListener(v -> startRestore());

        autoButton = findViewById(R.id.btn_ext_auto);
        autoButton.setOnClickListener(v -> {
            boolean enabled = !em.isAutoUpdateEnabled();
            em.setAutoUpdateEnabled(enabled);
            updateAutoButton();
            toast(enabled ? "Automatic updates enabled (every 12 h)"
                    : "Automatic updates disabled");
        });

        adapter = new Adapter();
        ListView list = findViewById(R.id.ext_list);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.ext_empty));
        list.setOnItemClickListener((parent, view, position, id) ->
                showDetailsDialog(adapter.getItem(position)));

        adapter.setData(em.getInstalled());
        updateAutoButton();
        em.refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ExtensionManager.get().setCurrentActivity(this);
    }

    @Override
    protected void onPause() {
        ExtensionManager.get().setCurrentActivity(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // [M8] Dismiss everything still showing before the activity dies.
        for (Dialog d : new ArrayList<>(activeDialogs)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {
            }
        }
        activeDialogs.clear();
        ExtensionManager.get().removeListener(listener);
        ExtensionManager.get().onActivityDestroyed(this);
        super.onDestroy();
    }

    private void onExtensionsChanged() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                adapter.setData(ExtensionManager.get().getInstalled());
            }
        });
    }

    private void updateAutoButton() {
        autoButton.setText(ExtensionManager.get().isAutoUpdateEnabled()
                ? "Auto-update: ON" : "Auto-update: OFF");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Dialog tracking ([M8])
    // ------------------------------------------------------------------
    private void trackDialog(Dialog d) {
        activeDialogs.add(d);
        d.setOnDismissListener(x -> activeDialogs.remove(d));
    }

    // ------------------------------------------------------------------
    // Install dialog
    // ------------------------------------------------------------------

    private void showInstallDialog() {
        final LinearLayout content = dialogContent(20);

        content.addView(text("Add extension", 0xFFFFFFFF, 17, true));

        final EditText url = new EditText(this);
        url.setHint("https://addons.mozilla.org/android/…/file.xpi");
        url.setTextColor(0xFFFFFFFF);
        url.setHintTextColor(0x66FFFFFF);
        url.setTextSize(13);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setBackgroundResource(R.drawable.bg_glass_input);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        ulp.topMargin = dp(12);
        content.addView(url, ulp);

        TextView install = button("Install from URL", false);
        TextView ubo = button("Quick add: uBlock Origin", false);
        TextView browse = button("Browse addons.mozilla.org", false);
        TextView fromFile = button("Install from .xpi file…", false);
        TextView cancel = button("Cancel", true);
        content.addView(install);
        content.addView(ubo);
        content.addView(browse);
        content.addView(fromFile);
        content.addView(cancel);

        final AlertDialog dialog = glassDialog(new ScrollView(this), content);

        install.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            dialog.dismiss();
            if (u.isEmpty()) { toast("Enter a .xpi URL first"); return; }
            ExtensionManager.get().installFromUrl(u, null);
        });
        ubo.setOnClickListener(v -> {
            dialog.dismiss();
            ExtensionManager.get().installFromUrl(UBLOCK_URL, null);
        });
        browse.setOnClickListener(v -> {
            dialog.dismiss();
            ExtensionManager.get().openInBrowserTab(AMO_ANDROID);
            finish();
        });
        fromFile.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQ_INSTALL_FILE);
            } catch (Exception e) {
                toast("No file picker available");
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
    }

    // ------------------------------------------------------------------
    // Details dialog
    // ------------------------------------------------------------------

    private void showDetailsDialog(final WebExtension ext) {
        if (ext == null) return;
        final ExtensionManager em = ExtensionManager.get();

        LinearLayout content = dialogContent(20);
        content.addView(text(ExtensionManager.displayName(ext), 0xFFFFFFFF, 17, true));

        String meta = "";
        if (ext.metaData != null) {
            if (ext.metaData.version != null) meta = ext.metaData.version;
            if (ext.metaData.creatorName != null) {
                meta += (meta.isEmpty() ? "" : " • ") + ext.metaData.creatorName;
            }
        }
        TextView metaView = text(meta, 0x99FFFFFF, 12, false);
        metaView.setPadding(0, dp(2), 0, dp(6));
        content.addView(metaView);

        if (ext.metaData != null && ext.metaData.description != null) {
            TextView desc = text(ext.metaData.description, 0xB3FFFFFF, 13, false);
            desc.setMaxLines(4);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(desc);
        }

        if (ext.metaData != null) {
            String[] perms = ExtensionManager.concat(
                    ext.metaData.requiredPermissions, ext.metaData.requiredOrigins, null);
            TextView permHeader = text(
                    perms.length == 0 ? "Permissions: none" : "Permissions:",
                    0xB3FFFFFF, 12, true);
            permHeader.setPadding(0, dp(10), 0, 0);
            content.addView(permHeader);
            for (String p : perms) {
                TextView row = text("• " + p, 0x99FFFFFF, 12, false);
                row.setPadding(dp(8), dp(2), 0, 0);
                content.addView(row);
            }
        }

        boolean enabled = ext.metaData == null || ext.metaData.enabled;
        TextView state = text(enabled ? "Enabled" : "Disabled",
                enabled ? 0xB3FFFFFF : 0xFFFF8888, 12, true);
        state.setPadding(0, dp(10), 0, 0);
        content.addView(state);

        TextView update = button("Check for update", false);
        content.addView(update);

        TextView options = null;
        if (ext.metaData != null && ext.metaData.optionsPageUrl != null) {
            options = button("Open options page", false);
            content.addView(options);
        }
        TextView homepage = null;
        if (ext.metaData != null && ext.metaData.homepageUrl != null) {
            homepage = button("Open homepage", false);
            content.addView(homepage);
        }
        TextView remove = button("Remove", true);
        content.addView(remove);
        TextView close = button("Close", false);
        content.addView(close);

        final AlertDialog dialog = glassDialog(new ScrollView(this), content);

        update.setOnClickListener(v -> {
            dialog.dismiss();
            em.updateExtension(ext, null);
        });        
        if (options != null) options.setOnClickListener(v -> {
            dialog.dismiss();
            em.openInBrowserTab(ext.metaData.optionsPageUrl);
            finish();   // [F12] reveal the tab that was just opened
        });
        if (homepage != null) homepage.setOnClickListener(v -> {
            dialog.dismiss();
            em.openInBrowserTab(ext.metaData.homepageUrl);
            finish();   // [F12] reveal the tab that was just opened
        });
        remove.setOnClickListener(v -> {
            dialog.dismiss();
            trackDialog(new AlertDialog.Builder(ExtensionsActivity.this)
                    .setTitle("Remove " + ExtensionManager.displayName(ext) + "?")
                    .setMessage("The extension and its data will be removed.")
                    .setPositiveButton("Remove", (d, w) -> em.uninstall(ext, null))
                    .setNegativeButton("Cancel", null)
                    .show());
        });
        close.setOnClickListener(v -> dialog.dismiss());
    }

    // ------------------------------------------------------------------
    // Backup / restore / file install plumbing
    // ------------------------------------------------------------------

    private void startBackup() {
        int count = 0;
        for (WebExtension ext : ExtensionManager.get().getInstalled()) {
            if (!ext.isBuiltIn) count++;
        }
        if (count == 0) { toast("No extensions to back up"); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "spoongecko-extensions-backup.json");
        try {
            startActivityForResult(intent, REQ_BACKUP);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    private void startRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try {
            startActivityForResult(intent, REQ_RESTORE);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_BACKUP) {
            writeBackup(uri);
        } else if (requestCode == REQ_RESTORE) {
            readRestore(uri);
        } else if (requestCode == REQ_INSTALL_FILE) {
            try {
                File xpi = copyToCache(uri);
                ExtensionManager.get().installFromFile(xpi, null);
            } catch (Exception e) {
                Log.w(TAG, "file install failed", e);
                toast("Could not read the selected file");
            }
        }
    }

    private void writeBackup(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
            String json = ExtensionManager.get().buildBackupJson();
            if (json == null || os == null) { toast("Backup failed"); return; }
            os.write(json.getBytes(StandardCharsets.UTF_8));
            toast("Backup saved");
        } catch (Exception e) {
            Log.w(TAG, "backup failed", e);
            toast("Backup failed: " + e.getMessage());
        }
    }

    private void readRestore(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) { toast("Could not open the file"); return; }
            JSONObject root = new JSONObject(new String(readFully(is), StandardCharsets.UTF_8));
            JSONArray entries = root.optJSONArray("extensions");
            if (entries == null || entries.length() == 0) {
                toast("The backup file contains no extensions");
                return;
            }
            toast("Restoring " + entries.length() + " extension(s)…");
            ExtensionManager.get().restoreFromBackup(entries, (restored, failed, failures) -> {
                if (failures != null && !failures.isEmpty()) {
                    showRestoreFailures(restored, failed, failures);
                } else {
                    toast(restored + " extension(s) restored"
                            + (failed > 0 ? ", " + failed + " failed" : ""));
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
            toast("Restore failed: " + e.getMessage());
        }
    }

    private void showRestoreFailures(int restored, int failed, String failures) {
        LinearLayout content = dialogContent(20);
        content.addView(text("Restore finished", 0xFFFFFFFF, 17, true));
        TextView summary = text(restored + " restored, " + failed + " failed",
                0x99FFFFFF, 13, false);
        summary.setPadding(0, dp(4), 0, dp(8));
        content.addView(summary);
        TextView details = text(failures, 0x99FFFFFF, 12, false);
        content.addView(details);
        TextView ok = button("OK", false);
        content.addView(ok);
        final AlertDialog dialog = glassDialog(new ScrollView(this), content);
        ok.setOnClickListener(v -> dialog.dismiss());
    }

    private File copyToCache(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("could not open file");
            File file = new File(getCacheDir(), "ext-" + System.currentTimeMillis() + ".xpi");
            try (OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return file;
        }
    }

    private byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // List adapter
    // ------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        private List<WebExtension> data = new ArrayList<>();

        void setData(List<WebExtension> d) {
            data = d != null ? d : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public WebExtension getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_extension, parent, false);
            final WebExtension ext = getItem(position);

            ImageView icon = v.findViewById(R.id.ext_icon);
            TextView name = v.findViewById(R.id.ext_name);
            TextView version = v.findViewById(R.id.ext_version);
            TextView desc = v.findViewById(R.id.ext_desc);
            Switch toggle = v.findViewById(R.id.ext_switch);

            name.setText(ExtensionManager.displayName(ext));
            version.setText(ext.metaData != null && ext.metaData.version != null
                    ? ext.metaData.version : "");
            desc.setText(ext.metaData != null && ext.metaData.description != null
                    ? ext.metaData.description : "");

            boolean enabled = ext.metaData == null || ext.metaData.enabled;
            name.setAlpha(enabled ? 1f : 0.5f);
            desc.setAlpha(enabled ? 1f : 0.5f);

            icon.setTag(ext.id);
            icon.setImageResource(R.drawable.ic_extension);
            if (ext.metaData != null && ext.metaData.icon != null) {
                ext.metaData.icon.getBitmap(64).accept(bitmap -> {
                    if (bitmap != null && ext.id.equals(icon.getTag())) {
                        icon.setImageBitmap(bitmap);
                    }
                });
            }

            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(enabled);
            toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                    ExtensionManager.get().setExtensionEnabled(ext, isChecked, null));

            return v;
        }
    }

    // ------------------------------------------------------------------
    // Small view helpers (match the app's glass style)
    // ------------------------------------------------------------------

    private LinearLayout dialogContent(int padDp) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_glass_dialog);
        int pad = dp(padDp);
        content.setPadding(pad, pad, pad, dp(12));
        return content;
    }

    private TextView text(String s, int color, float sizeSp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean red) {
        TextView b = text(label, red ? 0xFFFF8888 : 0xFFFFFFFF, 13, false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(red ? R.drawable.bg_glass_button_red : R.drawable.bg_glass_button);
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private AlertDialog glassDialog(ScrollView scroll, View content) {
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(scroll);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        trackDialog(dialog);                              // [M8]
        dialog.show();
        return dialog;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
