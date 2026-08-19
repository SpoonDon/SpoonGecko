package com.spoongecko.app;

import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ExtensionsActivity extends AppCompatActivity {

    private static final String AMO_URL =
            "https://addons.mozilla.org/en-US/firefox/extensions/";
    private static final long MAX_XPI_BYTES = 20L * 1024 * 1024;

    private RecyclerView recyclerView;
    private TextView emptyView;
    private ExtensionAdapter adapter;
    private final List<WebExtension> installedExtensions = new ArrayList<>();
    private ActivityResultLauncher<String[]> openXpiLauncher;
    private ActivityResultLauncher<String> createBackupLauncher;
    private ActivityResultLauncher<String[]> restoreBackupLauncher;
    private ExtensionPopupController popupController;

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
            disabled.setPadding(UiUtils.dp(this, 32), UiUtils.dp(this, 64),
                    UiUtils.dp(this, 32), 0);
            disabled.setTextColor(getResources().getColor(
                    R.color.md_theme_on_surface_variant, null));
            root.addView(disabled);
            setContentView(root);
            return;
        }

        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(container);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiUtils.dp(this, 16), UiUtils.dp(this, 16),
                UiUtils.dp(this, 16), UiUtils.dp(this, 16));
        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);

        LinearLayout installRow = new LinearLayout(this);
        installRow.setOrientation(LinearLayout.HORIZONTAL);
        installRow.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton btnInstall = new MaterialButton(this);
        btnInstall.setText(R.string.install_xpi);
        btnInstall.setOnClickListener(v -> openXpiLauncher.launch(new String[]{"*/*"}));
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        installParams.setMargins(0, 0, UiUtils.dp(this, 8), 0);
        btnInstall.setLayoutParams(installParams);

        MaterialButton btnBrowseAmo = new MaterialButton(this);
        btnBrowseAmo.setText(R.string.browse_amo);
        btnBrowseAmo.setOnClickListener(v -> RuntimeController.openUrlInMain(this, AMO_URL));
        btnBrowseAmo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        installRow.addView(btnInstall);
        installRow.addView(btnBrowseAmo);
        actions.addView(installRow);

        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        backupRow.setGravity(Gravity.CENTER_VERTICAL);
        backupRow.setPadding(0, UiUtils.dp(this, 8), 0, 0);

        MaterialButton btnBackup = new MaterialButton(this);
        btnBackup.setText(R.string.backup_extensions);
        btnBackup.setOnClickListener(v -> requestBackup());
        LinearLayout.LayoutParams backupParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        backupParams.setMargins(0, 0, UiUtils.dp(this, 8), 0);
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

        emptyView = new TextView(this);
        emptyView.setText(R.string.no_extensions);
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, UiUtils.dp(this, 32), 0, 0);
        emptyView.setLineSpacing(UiUtils.dp(this, 4), 1);
        emptyView.setTextColor(getResources().getColor(
                R.color.md_theme_on_surface_variant, null));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        listParams.topMargin = UiUtils.dp(this, 12);
        recyclerView.setLayoutParams(listParams);
        adapter = new ExtensionAdapter();
        recyclerView.setAdapter(adapter);
        content.addView(recyclerView);

        popupController = new ExtensionPopupController(
                this, container, MainActivity.getGeckoRuntime());
        ExtensionActionManager.getInstance().setPopupOpener(popupController::openPopup);

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
        if (popupController != null) popupController.closePopup();
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
                runOnUiThread(() -> {
                    DiffUtil.DiffResult diff =
                            DiffUtil.calculateDiff(new ExtensionDiff(installedExtensions, extensions));
                    installedExtensions.clear();
                    installedExtensions.addAll(extensions);
                    diff.dispatchUpdatesTo(adapter);
                    ExtensionSessionManager.getInstance().setExtensions(extensions);
                    ExtensionSessionManager.getInstance().syncAll();
                    updateEmptyState();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateEmptyState() {
        boolean empty = installedExtensions.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private class ExtensionAdapter extends RecyclerView.Adapter<ExtensionAdapter.Holder> {

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(parent.getContext());
            card.setRadius(UiUtils.dp(parent.getContext(), 12));
            card.setCardElevation(UiUtils.dp(parent.getContext(), 1));
            RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = UiUtils.dp(parent.getContext(), 8);
            card.setLayoutParams(cardParams);

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(UiUtils.dp(parent.getContext(), 16), UiUtils.dp(parent.getContext(), 12),
                    UiUtils.dp(parent.getContext(), 8), UiUtils.dp(parent.getContext(), 12));
            row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView nameView = new TextView(parent.getContext());
            nameView.setTextSize(15);
            nameView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
            info.addView(nameView);

            TextView versionView = new TextView(parent.getContext());
            versionView.setTextSize(11);
            versionView.setTextColor(getResources().getColor(
                    R.color.md_theme_on_surface_variant, null));
            info.addView(versionView);

            TextView idView = new TextView(parent.getContext());
            idView.setTextSize(10);
            idView.setTextColor(getResources().getColor(
                    R.color.md_theme_on_surface_variant, null));
            info.addView(idView);

            TextView badge = new TextView(parent.getContext());
            badge.setTextSize(11);
            badge.setPadding(0, UiUtils.dp(parent.getContext(), 2), 0, 0);
            info.addView(badge);

            row.addView(info);

            MaterialButton btnPopup = new MaterialButton(parent.getContext());
            btnPopup.setText(R.string.extension_popup);
            btnPopup.setTextSize(12);
            LinearLayout.LayoutParams popupParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            popupParams.setMargins(UiUtils.dp(parent.getContext(), 8), 0,
                    UiUtils.dp(parent.getContext(), 8), 0);
            btnPopup.setLayoutParams(popupParams);
            row.addView(btnPopup);

            MaterialButton btnOptions = new MaterialButton(parent.getContext());
            btnOptions.setText(R.string.settings_title);
            btnOptions.setTextSize(12);
            LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            optionsParams.setMargins(UiUtils.dp(parent.getContext(), 8), 0,
                    UiUtils.dp(parent.getContext(), 8), 0);
            btnOptions.setLayoutParams(optionsParams);
            row.addView(btnOptions);

            SwitchCompat toggle = new SwitchCompat(parent.getContext());
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            toggleParams.setMargins(UiUtils.dp(parent.getContext(), 8), 0,
                    UiUtils.dp(parent.getContext(), 8), 0);
            toggle.setLayoutParams(toggleParams);
            row.addView(toggle);

            MaterialButton btnRemove = new MaterialButton(parent.getContext());
            btnRemove.setText(R.string.remove);
            btnRemove.setTextSize(12);
            row.addView(btnRemove);

            card.addView(row);
            return new Holder(card, nameView, versionView, idView, badge,
                    btnPopup, btnOptions, toggle, btnRemove);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            WebExtension ext = installedExtensions.get(position);
            holder.ext = ext;
            holder.nameView.setText(ExtensionController.getDisplayName(ext));

            String version = ExtensionController.getVersion(ext);
            holder.versionView.setText(version.isEmpty()
                    ? ""
                    : getString(R.string.extension_version, version));
            holder.versionView.setVisibility(version.isEmpty() ? View.GONE : View.VISIBLE);

            holder.idView.setText(ext.id != null ? ext.id : "");

            boolean enabled = extensionEnabled(ext);
            holder.badge.setText(enabled ? R.string.extension_on : R.string.extension_off);
            holder.badge.setTextColor(enabled
                    ? getResources().getColor(R.color.md_theme_primary, null)
                    : getResources().getColor(R.color.md_theme_on_surface_variant, null));

            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(enabled);
            holder.toggle.setContentDescription(enabled
                    ? getString(R.string.disable_extension)
                    : getString(R.string.enable_extension));
            holder.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
                btn.setContentDescription(isChecked
                        ? getString(R.string.disable_extension)
                        : getString(R.string.enable_extension));
                if (isChecked) {
                    enableExtension(ext);
                } else {
                    disableExtension(ext);
                }
            });

            holder.btnPopup.setOnClickListener(v -> {
                boolean clicked = ExtensionActionManager.getInstance().click(ext);
                if (!clicked) {
                    Toast.makeText(ExtensionsActivity.this,
                            R.string.extension_no_popup, Toast.LENGTH_SHORT).show();
                }
            });
            holder.btnOptions.setOnClickListener(v -> openExtensionSettings(ext));
            holder.btnRemove.setOnClickListener(v -> confirmAndRemove(ext));
            holder.card.setOnClickListener(v -> openExtensionSettings(ext));
        }

        @Override
        public int getItemCount() {
            return installedExtensions.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView nameView;
            final TextView versionView;
            final TextView idView;
            final TextView badge;
            final MaterialButton btnPopup;
            final MaterialButton btnOptions;
            final SwitchCompat toggle;
            final MaterialButton btnRemove;
            WebExtension ext;

            Holder(MaterialCardView card, TextView nameView, TextView versionView, TextView idView,
                   TextView badge, MaterialButton btnPopup, MaterialButton btnOptions,
                   SwitchCompat toggle, MaterialButton btnRemove) {
                super(card);
                this.card = card;
                this.nameView = nameView;
                this.versionView = versionView;
                this.idView = idView;
                this.badge = badge;
                this.btnPopup = btnPopup;
                this.btnOptions = btnOptions;
                this.toggle = toggle;
                this.btnRemove = btnRemove;
            }
        }
    }

    private class ExtensionDiff extends DiffUtil.Callback {
        private final List<WebExtension> oldList;
        private final List<WebExtension> newList;

        ExtensionDiff(List<WebExtension> oldList, List<WebExtension> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            String oldId = oldList.get(oldPos).id;
            String newId = newList.get(newPos).id;
            return oldId != null ? oldId.equals(newId) : newId == null;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            WebExtension a = oldList.get(oldPos);
            WebExtension b = newList.get(newPos);
            if (extensionEnabled(a) != extensionEnabled(b)) return false;
            if (!strEq(ExtensionController.getDisplayName(a),
                    ExtensionController.getDisplayName(b))) return false;
            return strEq(ExtensionController.getVersion(a),
                    ExtensionController.getVersion(b));
        }

        private boolean strEq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private boolean extensionEnabled(WebExtension ext) {
        if (ext.metaData != null) return ExtensionController.isEnabled(ext);
        return ExtensionController.isEnabledInPrefs(this, ext.id);
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
        boolean enabled = extensionEnabled(ext);
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
        restoreAll(entries);
    }

    private void restoreAll(List<ExtensionBackupManager.BackupEntry> entries) {
        int total = entries.size();
        AtomicInteger restored = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(total);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();

        for (ExtensionBackupManager.BackupEntry entry : entries) {
            executor.execute(() -> {
                ExtensionController.install(
                        this,
                        ExtensionBackupManager.amoLatestUrl(entry.id),
                        runtime,
                        new ExtensionController.Callback() {
                            @Override
                            public void onSuccess(String message) {
                                restored.incrementAndGet();
                                if (remaining.decrementAndGet() == 0) {
                                    finishRestore(restored.get(), failed.get());
                                }
                            }

                            @Override
                            public void onError(String message) {
                                failed.incrementAndGet();
                                if (remaining.decrementAndGet() == 0) {
                                    finishRestore(restored.get(), failed.get());
                                }
                            }
                        });
            });
        }
        executor.shutdown();
    }

    private void finishRestore(int restored, int failed) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, getString(R.string.backup_restore_done, restored, failed),
                    Toast.LENGTH_LONG).show();
            refreshExtensionsList();
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

        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.install(this, uri.toString(), runtime,
                new ExtensionController.Callback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(ExtensionsActivity.this, message, Toast.LENGTH_SHORT).show();
                            refreshExtensionsList();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        installExtensionFromCopy(uri, runtime);
                    }
                });
    }

    private void installExtensionFromCopy(Uri uri, GeckoRuntime runtime) {
        File tempXpi = new File(getCacheDir(), "xpi_install_" + System.currentTimeMillis() + ".xpi");
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempXpi)) {
                if (in == null) throw new IOException("Cannot open input stream for URI");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (IOException e) {
            tempXpi.delete();
            showInstallError(getString(R.string.could_not_read_file));
            return;
        }

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
            try {
                return Long.parseLong(sizeStr);
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private String queryColumn(Uri uri, String column) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(column);
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getString(idx);
            }
        } catch (RuntimeException ignored) {}
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
}
