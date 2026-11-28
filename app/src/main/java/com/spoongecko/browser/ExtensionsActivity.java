package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.WebExtension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spoongecko-ext-io");
        t.setDaemon(true);
        return t;
    });

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
        ioExecutor.shutdownNow();
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

        final AlertDialog dialog = glassDialog(content);

        install.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (u.isEmpty()) {
                toast("Enter a .xpi URL first");
                return;
            }
            if (!ExtensionManager.isHttpUrl(u)) {
                toast("Enter a valid https:// or http:// .xpi URL");
                return;
            }
            dialog.dismiss();
            ExtensionManager.get().installFromUrl(u, null);
        });
        ubo.setOnClickListener(v -> {
            dialog.dismiss();
            ExtensionManager.get().installFromUrl(UBLOCK_URL, null);
        });
        browse.setOnClickListener(v -> {
            dialog.dismiss();
            openBrowserTabAndFinish(AMO_ANDROID);
        });
        fromFile.setOnClickListener(v -> {
            dialog.dismiss();
            launchOpenPicker(REQ_INSTALL_FILE, "*/*", null);
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

        final AlertDialog dialog = glassDialog(content);

        update.setOnClickListener(v -> {
            dialog.dismiss();
            em.updateExtension(ext, null);
        });        
        if (options != null) options.setOnClickListener(v -> {
            dialog.dismiss();
            openBrowserTabAndFinish(ext.metaData.optionsPageUrl);
        });
        if (homepage != null) homepage.setOnClickListener(v -> {
            dialog.dismiss();
            openBrowserTabAndFinish(ext.metaData.homepageUrl);
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

    private void openBrowserTabAndFinish(String url) {
        ExtensionManager.get().openInBrowserTab(url);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
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
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "spoongecko-extensions-backup.json");
        try {
            startActivityForResult(intent, REQ_BACKUP);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    private void startRestore() {
        // Many file managers tag .json backups as text/plain or application/octet-stream.
        launchOpenPicker(REQ_RESTORE, "*/*", new String[]{
                "application/json", "text/json", "text/plain", "application/octet-stream"
        });
    }

    private void launchOpenPicker(int requestCode, String primaryMime, String[] extraMimes) {
        if (tryLaunchPicker(Intent.ACTION_OPEN_DOCUMENT, requestCode, primaryMime, extraMimes)) {
            return;
        }
        if (!tryLaunchPicker(Intent.ACTION_GET_CONTENT, requestCode, primaryMime, extraMimes)) {
            toast("No file picker available");
        }
    }

    private boolean tryLaunchPicker(String action, int requestCode,
                                    String primaryMime, String[] extraMimes) {
        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(primaryMime);
        if (extraMimes != null && extraMimes.length > 0) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, extraMimes);
        }
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"));
        }
        try {
            startActivityForResult(intent, requestCode);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "picker launch failed for " + action, e);
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (!isSupportedUri(uri)) {
            toast("Unsupported file source");
            return;
        }
        if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
            }
        }
        if (requestCode == REQ_BACKUP) {
            writeBackup(uri);
        } else if (requestCode == REQ_RESTORE) {
            readRestore(uri);
        } else if (requestCode == REQ_INSTALL_FILE) {
            // Open stream synchronously while transient Intent URI grants are active.
            final FilePicker.ResolvedStream openedStream;
            try {
                openedStream = openResolvedStream(uri);
            } catch (Exception e) {
                Log.w(TAG, "file install open failed", e);
                toast("Could not read the selected file");
                return;
            }
            toast("Preparing extension…");
            ioExecutor.execute(() -> {
                try {
                    final File xpi = copyStreamToCache(openedStream);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            ExtensionManager.get().installFromFile(xpi, null);
                        } else {
                            xpi.delete();
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "file install failed", e);
                    runOnUiThread(() -> toast("Could not read the selected file"));
                }
            });
        }
    }

    private boolean isSupportedUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (TextUtils.isEmpty(scheme)) return false;
        String normalized = scheme.toLowerCase(Locale.ROOT);
        return ContentResolver.SCHEME_CONTENT.equals(normalized)
                || ContentResolver.SCHEME_FILE.equals(normalized);
    }

    private void writeBackup(Uri uri) {
        final String json = ExtensionManager.get().buildBackupJson();
        if (json == null) { toast("Backup failed"); return; }
        final OutputStream os;
        try {
            os = getContentResolver().openOutputStream(uri, "w");
        } catch (Exception e) {
            Log.w(TAG, "backup failed", e);
            toast("Backup failed: " + e.getMessage());
            return;
        }
        if (os == null) { toast("Backup failed"); return; }
        ioExecutor.execute(() -> {
            try (OutputStream out = os) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) toast("Backup saved");
                });
            } catch (Exception e) {
                Log.w(TAG, "backup failed", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) toast("Backup failed: " + e.getMessage());
                });
            }
        });
    }

    private void readRestore(Uri uri) {
        final FilePicker.ResolvedStream opened;
        try {
            opened = openResolvedStream(uri);
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
            toast("Restore failed: " + e.getMessage());
            return;
        }
        ioExecutor.execute(() -> {
            try (FilePicker.ResolvedStream stream = opened) {
                final JSONObject root = new JSONObject(
                        new String(readFully(stream.inputStream), StandardCharsets.UTF_8));
                final JSONArray entries = root.optJSONArray("extensions");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (entries == null || entries.length() == 0) {
                        toast("The backup file contains no extensions");
                        return;
                    }
                    toast("Restoring " + entries.length() + " extension(s)…");
                    ExtensionManager.get().restoreFromBackup(entries, (restored, failed, failures) -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (failures != null && !failures.isEmpty()) {
                            showRestoreFailures(restored, failed, failures);
                        } else {
                            toast(restored + " extension(s) restored"
                                    + (failed > 0 ? ", " + failed + " failed" : ""));
                        }
                    });
                });
            } catch (Exception e) {
                Log.w(TAG, "restore failed", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) toast("Restore failed: " + e.getMessage());
                });
            }
        });
    }

    private FilePicker.ResolvedStream openResolvedStream(Uri uri) throws Exception {
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
            final String path = uri.getPath();
            if (path == null) throw new Exception("could not open file");
            final File file = new File(path);
            if (FilePicker.isRestrictedLocalFile(this, file)) {
                throw new SecurityException("Restricted local file path");
            }
            return new FilePicker.ResolvedStream(new FileInputStream(file), null, null);
        }
        final FilePicker.ResolvedStream stream = FilePicker.openBestStream(this, getContentResolver(), uri);
        if (stream == null) throw new Exception("could not open file");
        return stream;
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
        final AlertDialog dialog = glassDialog(content);
        ok.setOnClickListener(v -> dialog.dismiss());
    }

    private static final long MAX_XPI_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_BACKUP_BYTES = 4 * 1024 * 1024;

    private File copyStreamToCache(FilePicker.ResolvedStream resolved) throws Exception {
        File file = null;
        try (FilePicker.ResolvedStream stream = resolved) {
            file = new File(getCacheDir(), "ext-" + System.currentTimeMillis() + ".xpi");
            try (OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[8192];
                long total = 0L;
                int n;
                while ((n = stream.inputStream.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_XPI_BYTES) {
                        throw new Exception("Extension file is too large");
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            return file;
        } catch (Exception e) {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            throw e;
        }
    }

    private byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BACKUP_BYTES) {
                throw new Exception("Backup file is too large");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // List adapter
    // ------------------------------------------------------------------

    private static final class ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView version;
        final TextView desc;
        final Switch toggle;

        ViewHolder(View v) {
            this.icon = v.findViewById(R.id.ext_icon);
            this.name = v.findViewById(R.id.ext_name);
            this.version = v.findViewById(R.id.ext_version);
            this.desc = v.findViewById(R.id.ext_desc);
            this.toggle = v.findViewById(R.id.ext_switch);
        }
    }

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
            final View v;
            final ViewHolder h;
            if (convertView == null) {
                v = getLayoutInflater().inflate(R.layout.item_extension, parent, false);
                h = new ViewHolder(v);
                v.setTag(h);
            } else {
                v = convertView;
                h = (ViewHolder) v.getTag();
            }
            final WebExtension ext = getItem(position);

            h.name.setText(ExtensionManager.displayName(ext));
            h.version.setText(ext.metaData != null && ext.metaData.version != null
                    ? ext.metaData.version : "");
            h.desc.setText(ext.metaData != null && ext.metaData.description != null
                    ? ext.metaData.description : "");

            boolean enabled = ext.metaData == null || ext.metaData.enabled;
            h.name.setAlpha(enabled ? 1f : 0.5f);
            h.desc.setAlpha(enabled ? 1f : 0.5f);

            h.icon.setTag(ext.id);
            h.icon.setImageResource(R.drawable.ic_extension);
            if (ext.metaData != null && ext.metaData.icon != null) {
                ext.metaData.icon.getBitmap(64).accept(bitmap -> runOnUiThread(() -> {
                    if (bitmap != null && ext.id.equals(h.icon.getTag())) {
                        h.icon.setImageBitmap(bitmap);
                    }
                }));
            }

            h.toggle.setOnCheckedChangeListener(null);
            h.toggle.setChecked(enabled);
            h.toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
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
        int pad = dp(Math.max(0, padDp - 6));
        content.setPadding(pad, pad, pad, dp(6));
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

    private AlertDialog glassDialog(View content) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(DialogFit.wrapGlassScroll(this, content));
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
