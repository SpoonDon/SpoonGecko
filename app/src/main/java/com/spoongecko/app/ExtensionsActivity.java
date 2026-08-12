package com.spoongecko.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

public class ExtensionsActivity extends AppCompatActivity {

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

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Extensions");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        MaterialButton btnInstall = new MaterialButton(this);
        btnInstall.setText("Install Extension (.xpi)");
        btnInstall.setPadding(0, 16, 0, 16);
        btnInstall.setOnClickListener(v ->
                openXpiLauncher.launch(new String[]{"*/*"}));
        content.addView(btnInstall);

        TextView spacer = new TextView(this);
        spacer.setHeight(16);
        content.addView(spacer);

        extensionsList = new LinearLayout(this);
        extensionsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(extensionsList);

        TextView emptyText = new TextView(this);
        emptyText.setText("No extensions installed");
        emptyText.setTextSize(14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, 32, 0, 0);
        emptyText.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        extensionsList.addView(emptyText);

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);

        refreshExtensionsList();
    }

    private void refreshExtensionsList() {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        if (runtime == null) return;
        runtime.getWebExtensionController().list().accept(
                extensions -> {
                    installedExtensions.clear();
                    installedExtensions.addAll(extensions);
                    runOnUiThread(this::rebuildExtensionsList);
                },
                e -> runOnUiThread(() ->
                        Toast.makeText(this, "Failed to list extensions", Toast.LENGTH_SHORT).show())
        );
    }

    private void rebuildExtensionsList() {
        extensionsList.removeAllViews();
        if (installedExtensions.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText("No extensions installed");
            emptyText.setTextSize(14);
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setPadding(0, 32, 0, 0);
            emptyText.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
            extensionsList.addView(emptyText);
            return;
        }

        for (WebExtension ext : installedExtensions) {
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

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView name = new TextView(this);
            name.setText(ext.metaData != null && ext.metaData.name != null
                    ? ext.metaData.name : "Unknown Extension");
            name.setTextSize(15);
            name.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
            info.addView(name);

            TextView idView = new TextView(this);
            idView.setText(ext.id != null ? ext.id : "");
            idView.setTextSize(11);
            idView.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
            info.addView(idView);

            MaterialButton btnRemove = new MaterialButton(this);
            btnRemove.setText("Remove");
            btnRemove.setTextSize(12);
            btnRemove.setOnClickListener(v -> {
                GeckoRuntime runtime = MainActivity.getGeckoRuntime();
                if (runtime == null) return;
                runtime.getWebExtensionController().uninstall(ext).accept(
                        result -> runOnUiThread(() -> {
                            Toast.makeText(this, "Extension removed", Toast.LENGTH_SHORT).show();
                            refreshExtensionsList();
                        }),
                        e -> runOnUiThread(() ->
                                Toast.makeText(this, "Failed to remove extension", Toast.LENGTH_LONG).show())
                );
            });

            row.addView(info);
            row.addView(btnRemove);
            card.addView(row);
            extensionsList.addView(card);
        }
    }

    private void installExtension(Uri uri) {
        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
        if (runtime == null) {
            Toast.makeText(this, "Browser not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        runtime.getWebExtensionController().install(uri.toString()).accept(
                extension -> runOnUiThread(() -> {
                    Toast.makeText(this, "Extension installed", Toast.LENGTH_SHORT).show();
                    refreshExtensionsList();
                }),
                e -> runOnUiThread(() ->
                        Toast.makeText(this, "Install failed: " + e.getMessage(), Toast.LENGTH_LONG).show())
        );
    }
}
