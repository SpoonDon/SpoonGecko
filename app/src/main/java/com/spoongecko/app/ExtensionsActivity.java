package com.spoongecko.app;

import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExtensionsActivity extends AppCompatActivity {

    private static final String AMO_URL =
            "https://addons.mozilla.org/en-US/firefox/extensions/";
    private static final long MAX_XPI_BYTES = 20L * 1024 * 1024;

    private LinearLayout extensionsList;
    private List<WebExtension> installedExtensions = new ArrayList<>();
    private ActivityResultLauncher<String[]> openXpiLauncher;
    private ActivityResultLauncher<String> createBackupLauncher;
    private ActivityResultLauncher<String[]> restoreBackupLauncher;

    private int restoreIndex;
    private int restoredCount;
    private int failedCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawable(new ColorDrawable(
                getResources().getColor(R.color.md_theme_background, null)));

        openXpiLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) installExtension(uri);
                });

        createBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) exportBackupTo(uri);
                });

        restoreBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) importBackupFrom(uri);
                });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getResources().getColor(R.color.md_theme_background, null));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.extensions_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        if (!BuildConfig.EXTENSIONS_ENABLED) {
            TextView disabled = new TextView(this);
            disabled.setText(R.string.extensions_unavailable);
            disabled.setTextSize(15);
            disabled.setGravity(Gravity.CENTER);
            disabled.setPadding(32, 64, 32, 0);
            disabled.setTextColor(getResources().getColor(
                    R.color.md_theme_on_surface_variant, null));
            root.addView(disabled);
            setContentView(root);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);

        LinearLayout installRow = new LinearLayout(this);
        installRow.setOrientation(LinearLayout.HORIZONTAL);
        installRow.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton btnInstall = new MaterialButton(this);
        btnInstall.setText(R.string.install_xpi);
        btnInstall.setOnClickListener(v ->
                openXpiLauncher.launch(new String[]{"*/*"}));
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        installParams.setMargins(0, 0, 8, 0);
        btnInstall.setLayoutParams(installParams);

        MaterialButton btnBrowseAmo = new MaterialButton(this);
        btnBrowseAmo.setText(R.string.browse_amo);
        btnBrowseAmo.setOnClickListener(v ->
                RuntimeController.openUrlInMain(this, AMO_URL));
        btnBrowseAmo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        installRow.addView(btnInstall);
        installRow.addView(btnBrowseAmo);
        actions.addView(installRow);

        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        backupRow.setGravity(Gravity.CENTER_VERTICAL);
        backupRow.setPadding(0, 8, 0, 0);

        MaterialButton btnBackup = new MaterialButton(this);
        btnBackup.setText(R.string.backup_extensions);
        btnBackup.setOnClickListener(v -> requestBackup());
        LinearLayout.LayoutParams backupParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        backupParams.setMargins(0, 0, 8, 0);
        btnBackup.setLayoutParams(backupParams);

        MaterialButton btnRestore = new MaterialButton(this);
        btnRestore.setText(R.string.restore_extensions);
        btnRestore.setOnClickListener(v -> requestRestore());
        btnRestore.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        backupRow.addView(btnBackup);
        backupRow.addView(btnRestore);
        actions.addView(backupRow);

        content.addView(actions);

        addSpacer(content, 16);

        extensionsList = new LinearLayout(this);
        extensionsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(extensionsList);

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!BuildConfig.EXTENSIONS_ENABLED) return;
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        if (runtime != null) {
            runtime.getWebExtensionController()
                    .setPromptDelegate(new InstallPromptDelegate(this));
        }
        refreshExtensionsList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!BuildConfig.EXTENSIONS_ENABLED) return;
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        if (runtime != null) {
            runtime.getWebExtensionController().setPromptDelegate(null);
        }
    }

    private void refreshExtensionsList() {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.list(this, runtime, new ExtensionController.ListCallback() {
            @Override
            public void onResult(List<WebExtension> extensions) {
                installedExtensions.clear();
                installedExtensions.addAll(extensions);
                runOnUiThread(() -> rebuildExtensionsList());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void rebuildExtensionsList() {
        extensionsList.removeAllViews();

        if (installedExtensions.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText(R.string.no_extensions);
            emptyText.setTextSize(14);
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setPadding(0, 32, 0, 0);
            emptyText.setLineSpacing(4, 1);
            emptyText.setTextColor(getResources().getColor(
                    R.color.md_theme_on_surface_variant, null));
            extensionsList.addView(emptyText);
            return;
        }

        for (WebExtension ext : installedExtensions) {
            extensionsList.addView(buildExtensionCard(ext));
        }
    }

    private MaterialCardView buildExtensionCard(WebExtension ext) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(12);
        card.setCardElevation(1);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 8);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 12, 8, 12);
        row.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView nameView = new TextView(this);
        nameView.setText(ExtensionController.getDisplayName(ext));
        nameView.setTextSize(15);
        nameView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
        info.addView(nameView);

        String version = ExtensionController.getVersion(ext);
        if (!version.isEmpty()) {
            TextView versionView = new TextView(this);
            versionView.setText(getString(R.string.extension_version, version));
            versionView.setTextSize(11);
            versionView.setTextColor(getResources().getColor(
                    R.color.md_theme_on_surface_variant, null));
            info.addView(versionView);
        }

        TextView idView = new TextView(this);
        idView.setText(ext.id != null ? ext.id : "");
        idView.setTextSize(10);
        idView.setTextColor(getResources().getColor(
                R.color.md_theme_on_surface_variant, null));
        info.addView(idView);

        boolean enabled = (ext.metaData != null)
                ? ExtensionController.isEnabled(ext)
                : ExtensionController.isEnabledInPrefs(this, ext.id);
        TextView badge = new TextView(this);
        badge.setText(enabled ? R.string.extension_on : R.string.extension_off);
        badge.setTextSize(11);
        badge.setTextColor(enabled
                ? getResources().getColor(R.color.md_theme_primary, null)
                : getResources().getColor(R.color.md_theme_on_surface_variant, null));
        badge.setPadding(0, 2, 0, 0);
        info.addView(badge);

        row.addView(info);

        MaterialButton btnOptions = new MaterialButton(this);
        btnOptions.setText(R.string.settings_title);
        btnOptions.setTextSize(12);
        btnOptions.setOnClickListener(v -> openExtensionSettings(ext));
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        optionsParams.setMargins(8, 0, 8, 0);
        btnOptions.setLayoutParams(optionsParams);
        row.addView(btnOptions);

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(enabled);
        toggle.setContentDescription(enabled
                ? getString(R.string.disable_extension)
                : getString(R.string.enable_extension));
        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            btn.setContentDescription(isChecked
                    ? getString(R.string.disable_extension)
                    : getString(R.string.enable_extension));
            if (isChecked) {
                enableExtension(ext);
            } else {
                disableExtension(ext);
            }
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(8, 0, 8, 0);
        toggle.setLayoutParams(toggleParams);
        row.addView(toggle);

        MaterialButton btnRemove = new MaterialButton(this);
        btnRemove.setText(R.string.remove);
        btnRemove.setTextSize(12);
        btnRemove.setOnClickListener(v -> confirmAndRemove(ext));
        row.addView(btnRemove);

        card.setOnClickListener(v -> openExtensionSettings(ext));

        card.addView(row);
        return card;
    }

    private void openExtensionSettings(WebExtension ext) {
        String optionsUrl = ExtensionController.getOptionsPageUrl(ext);
        if (optionsUrl != null && !optionsUrl.isEmpty()) {
            RuntimeController.openUrlInMain(this, optionsUrl);
            return;
        }
        showDetailsDialog(ext);
    }

    private void showDetailsDialog(WebExtension ext) {
        String name = ExtensionController.getDisplayName(ext);
        String version = ExtensionController.getVersion(ext);
        String id = ext.id != null ? ext.id : "";
        boolean enabled = ExtensionController.isEnabled(ext);
        String state = enabled ? getString(R.string.extension_on)
                : getString(R.string.extension_off);
        String message = getString(R.string.extension_version, version)
                + "\n" + id + "\n" + state
                + "\n\n" + getString(R.string.extension_no_options);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void requestBackup() {
        if (installedExtensions.isEmpty()) {
            Toast.makeText(this, R.string.backup_no_extensions, Toast.LENGTH_SHORT).show();
            return;
        }
        createBackupLauncher.launch("spoongecko-extensions.json");
    }

    private void exportBackupTo(Uri uri) {
        boolean ok = ExtensionBackupManager.exportBackup(this, installedExtensions, uri);
        Toast.makeText(this,
                ok ? R.string.backup_exported : R.string.backup_export_failed,
                Toast.LENGTH_SHORT).show();
    }

    private void requestRestore() {
        restoreBackupLauncher.launch(new String[]{"application/json", "text/*", "*/*"});
    }

    private void importBackupFrom(Uri uri) {
        List<ExtensionBackupManager.BackupEntry> entries =
                ExtensionBackupManager.parseBackup(this, uri);
        if (entries == null) {
            Toast.makeText(this, R.string.backup_restore_failed_parse, Toast.LENGTH_SHORT).show();
            return;
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.backup_restore_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        restoreIndex = 0;
        restoredCount = 0;
        failedCount = 0;
        restoreNext(entries);
    }

    private void restoreNext(List<ExtensionBackupManager.BackupEntry> entries) {
        if (restoreIndex >= entries.size()) {
            Toast.makeText(this,
                    getString(R.string.backup_restore_done, restoredCount, failedCount),
                    Toast.LENGTH_LONG).show();
            refreshExtensionsList();
            return;
        }
        ExtensionBackupManager.BackupEntry entry = entries.get(restoreIndex);
        restoreIndex++;
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.install(
                this,
                ExtensionBackupManager.amoLatestUrl(entry.id),
                runtime,
                new ExtensionController.Callback() {
                    @Override
                    public void onSuccess(String message) {
                        restoredCount++;
                        runOnUiThread(() -> restoreNext(entries));
                    }

                    @Override
                    public void onError(String message) {
                        failedCount++;
                        runOnUiThread(() -> restoreNext(entries));
                    }
                });
    }

    private void installExtension(Uri uri) {
        if (!isXpiFile(uri)) {
            showInstallError(getString(R.string.not_xpi));
            return;
        }
        long size = getFileSize(uri);
        if (size > MAX_XPI_BYTES) {
            showInstallError(getString(R.string.xpi_too_large));
            return;
        }

        File tempXpi = new File(getCacheDir(), "xpi_install.xpi");
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempXpi)) {
                if (in == null) throw new IOException("Cannot open input stream for URI");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (IOException e) {
            showInstallError(getString(R.string.could_not_read_file));
            return;
        }

        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.install(this, Uri.fromFile(tempXpi).toString(), runtime,
                new ExtensionController.Callback() {
                    @Override
                    public void onSuccess(String message) {
                        tempXpi.delete();
                        runOnUiThread(() -> {
                            Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                            refreshExtensionsList();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        tempXpi.delete();
                        runOnUiThread(() -> showInstallError(message));
                    }
                });
    }

    private boolean isXpiFile(Uri uri) {
        String name = queryColumn(uri, OpenableColumns.DISPLAY_NAME);
        if (name == null) name = uri.getLastPathSegment();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".xpi");
    }

    private long getFileSize(Uri uri) {
        String sizeStr = queryColumn(uri, OpenableColumns.SIZE);
        if (sizeStr != null) {
            try { return Long.parseLong(sizeStr); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private String queryColumn(Uri uri, String column) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(column);
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void showInstallError(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.install_failed)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void enableExtension(WebExtension ext) {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.enable(ext, runtime, this, new ExtensionController.Callback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                    refreshExtensionsList();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                    refreshExtensionsList();
                });
            }
        });
    }

    private void disableExtension(WebExtension ext) {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.disable(ext, runtime, this, new ExtensionController.Callback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                    refreshExtensionsList();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                    refreshExtensionsList();
                });
            }
        });
    }

    private void confirmAndRemove(WebExtension ext) {
        String name = ExtensionController.getDisplayName(ext);
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_extension_title)
                .setMessage(getString(R.string.remove_extension_message, name))
                .setPositiveButton(R.string.remove, (dialog, which) -> removeExtension(ext))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void removeExtension(WebExtension ext) {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.uninstall(ext, runtime, this, new ExtensionController.Callback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                    refreshExtensionsList();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void addSpacer(LinearLayout parent, int heightDp) {
        TextView spacer = new TextView(this);
        int heightPx = (int) (heightDp * getResources().getDisplayMetrics().density);
        spacer.setHeight(heightPx);
        parent.addView(spacer);
    }
}
