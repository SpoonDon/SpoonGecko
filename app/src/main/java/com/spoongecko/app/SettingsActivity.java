package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import org.mozilla.geckoview.GeckoRuntime;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "spoon_prefs";
    private static final String PREF_SEARCH_ENGINE = "search_engine";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Settings");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setFitsSystemWindows(true);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        content.addView(buildCard("Search Engine", getSearchEngineLabel(), v -> {
            String[] engines = {"Brave", "DuckDuckGo", "Google"};
            new AlertDialog.Builder(this)
                    .setTitle("Search Engine")
                    .setItems(engines, (dialog, which) -> {
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        String value = which == 0 ? "brave" : which == 1 ? "duckduckgo" : "google";
                        prefs.edit().putString(PREF_SEARCH_ENGINE, value).apply();
                        ((TextView) ((LinearLayout) v.getParent()).findViewWithTag("subtitle_search"))
                                .setText(getSearchEngineLabel());
                    })
                    .show();
        }));

        content.addView(buildSimpleCard("Clear Browsing Data", v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Browsing Data")
                    .setMessage("This will close all tabs and clear browsing data.")
                    .setPositiveButton("Clear", (dialog, which) -> {
                        GeckoRuntime runtime = MainActivity.getGeckoRuntime();
                        if (runtime != null) {
                            runtime.getWebExtensionController().list().accept(
                                extensions -> {
                                    for (org.mozilla.geckoview.WebExtension ext : extensions) {
                                        runtime.getWebExtensionController().uninstall(ext);
                                    }
                                }
                            );
                        }
                        MainActivity.sGeckoRuntime = null;
                        Toast.makeText(this, "Data cleared. Restart the app.", Toast.LENGTH_LONG).show();
                        finishAffinity();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }));

        content.addView(buildSimpleCard("Site Permissions", v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Site Permissions")
                    .setMessage("Permissions handled:\n\nCamera\nMicrophone\nLocation\nMedia Autoplay\n\n"
                            + "Permissions are requested on-demand when websites need them. "
                            + "Manage via Android Settings -> Apps -> SpoonGecko -> Permissions.")
                    .setPositiveButton("Open App Settings", (dialog, which) -> {
                        android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton("Close", null)
                    .show();
        }));

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
    }

    private String getSearchEngineLabel() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String engine = prefs.getString(PREF_SEARCH_ENGINE, "brave");
        switch (engine) {
            case "google": return "Google";
            case "duckduckgo": return "DuckDuckGo";
            case "brave":
            default: return "Brave";
        }
    }

    private MaterialCardView buildCard(String title, String subtitle, android.view.View.OnClickListener listener) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(16);
        card.setCardElevation(2);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(20, 16, 20, 16);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(13);
        subtitleView.setTag("subtitle_search");
        subtitleView.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        subtitleView.setPadding(0, 4, 0, 0);

        inner.addView(titleView);
        inner.addView(subtitleView);
        card.addView(inner);
        card.setOnClickListener(listener);

        return card;
    }

    private MaterialCardView buildSimpleCard(String title, android.view.View.OnClickListener listener) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(16);
        card.setCardElevation(2);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
        titleView.setPadding(20, 20, 20, 20);

        card.addView(titleView);
        card.setOnClickListener(listener);

        return card;
    }
}
