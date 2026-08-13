package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;

/**
 * ExtensionsActivity manages installed GeckoView WebExtensions.
 *
 * <p>Features:
 * <ul>
 *   <li>List installed extensions with name, version, and enabled/disabled badge.</li>
 *   <li>Enable / disable individual extensions without uninstalling them.</li>
 *   <li>Install extensions from a local .xpi file via the file picker.</li>
 *   <li>Remove (uninstall) extensions with a confirmation dialog.</li>
 *   <li>Link to the Firefox Add-ons (AMO) catalogue for browsing compatible extensions.</li>
 *   <li>Graceful no-op / error UI when extension support is disabled via the build flag.</li>
 * </ul>
 *
 * <p>When {@link BuildConfig#EXTENSIONS_ENABLED} is {@code false}, all controls are
 * hidden and a prominent message is shown explaining that the feature is unavailable.
 */
public class ExtensionsActivity extends AppCompatActivity {

    private static final String AMO_URL =
            "https://addons.mozilla.org/en-US/firefox/extensions/";

    private LinearLayout extensionsList;
    private List<WebExtension> installedExtensions = new ArrayList<>();
    private ActivityResultLauncher<String[]> openXpiLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        openXpiLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) installExtension(uri);
                });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Extensions");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        if (!BuildConfig.EXTENSIONS_ENABLED) {
            // Feature disabled build – show informational message and exit early.
            TextView disabled = new TextView(this);
            disabled.setText("Extension support is not available in this build.");
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

        // ---- Action buttons row ----------------------------------------
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton btnInstall = new MaterialButton(this);
        btnInstall.setText("Install .xpi");
        btnInstall.setOnClickListener(v ->
                openXpiLauncher.launch(new String[]{"*/*"}));
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        installParams.setMargins(0, 0, 8, 0);
        btnInstall.setLayoutParams(installParams);

        MaterialButton btnBrowseAmo = new MaterialButton(this);
        btnBrowseAmo.setText("Browse AMO");
        btnBrowseAmo.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AMO_URL))));
        btnBrowseAmo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        actions.addView(btnInstall);
        actions.addView(btnBrowseAmo);
        content.addView(actions);

        // ---- Spacer --------------------------------------------------------
        addSpacer(content, 16);

        // ---- Extensions list -----------------------------------------------
        extensionsList = new LinearLayout(this);
        extensionsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(extensionsList);

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
        // Initial load is handled by onResume, which always follows onCreate.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (BuildConfig.EXTENSIONS_ENABLED) {
            refreshExtensionsList();
        }
    }

    // ---------------------------------------------------------------- refresh

    private void refreshExtensionsList() {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.list(runtime, new ExtensionController.ListCallback() {
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
            emptyText.setText("No extensions installed.\nTap \"Install .xpi\" to add one,"
                    + " or browse AMO for Firefox-compatible add-ons.");
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

    // ------------------------------------------------------------------ card

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
        row.setPadding(16, 12, 16, 12);

        // -- Info column -----------------------------------------------------
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
            versionView.setText("v" + version);
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

        // -- Enabled badge ---------------------------------------------------
        boolean enabled = (ext.metaData != null)
                ? ExtensionController.isEnabled(ext)
                : ExtensionController.isEnabledInPrefs(this, ext.id);
        TextView badge = new TextView(this);
        badge.setText(enabled ? "● ON" : "○ OFF");
        badge.setTextSize(11);
        badge.setTextColor(enabled
                ? getResources().getColor(R.color.md_theme_primary, null)
                : getResources().getColor(R.color.md_theme_on_surface_variant, null));
        badge.setPadding(0, 2, 0, 0);
        info.addView(badge);

        row.addView(info);

        // -- Enable/Disable toggle -------------------------------------------
        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(enabled);
        toggle.setContentDescription(enabled ? "Disable extension" : "Enable extension");
        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            btn.setContentDescription(isChecked ? "Disable extension" : "Enable extension");
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

        // -- Remove button ---------------------------------------------------
        MaterialButton btnRemove = new MaterialButton(this);
        btnRemove.setText("Remove");
        btnRemove.setTextSize(12);
        btnRemove.setOnClickListener(v -> confirmAndRemove(ext));
        row.addView(btnRemove);

        card.addView(row);
        return card;
    }

    // ---------------------------------------------------------------- actions

    private void installExtension(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        ExtensionController.install(uri.toString(), runtime, new ExtensionController.Callback() {
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
                        new AlertDialog.Builder(ExtensionsActivity.this)
                                .setTitle("Install Failed")
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .show());
            }
        });
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
                    refreshExtensionsList(); // re-sync toggle state
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
                    refreshExtensionsList(); // re-sync toggle state
                });
            }
        });
    }

    private void confirmAndRemove(WebExtension ext) {
        String name = ExtensionController.getDisplayName(ext);
        new AlertDialog.Builder(this)
                .setTitle("Remove Extension")
                .setMessage("Remove \"" + name + "\"? This cannot be undone.")
                .setPositiveButton("Remove", (dialog, which) -> removeExtension(ext))
                .setNegativeButton("Cancel", null)
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

    // ---------------------------------------------------------------- helpers

    private void addSpacer(LinearLayout parent, int heightDp) {
        TextView spacer = new TextView(this);
        int heightPx = (int) (heightDp * getResources().getDisplayMetrics().density);
        spacer.setHeight(heightPx);
        parent.addView(spacer);
    }
}
