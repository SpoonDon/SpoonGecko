package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

public class SettingsActivity extends AppCompatActivity {

    private TextView searchEngineSubtitle;
    private TextView downloadModeSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawable(new ColorDrawable(
                getResources().getColor(R.color.md_theme_background, null)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(getResources().getColor(R.color.md_theme_background, null));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.settings_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setFitsSystemWindows(true);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        content.addView(buildSearchEngineCard());

        content.addView(buildDownloadModeCard());

        content.addView(buildSimpleCard(R.string.clear_browsing_data_title, v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.clear_browsing_data_title)
                    .setMessage(R.string.clear_browsing_data_message)
                    .setPositiveButton(R.string.clear, (dialog, which) -> {
                        RuntimeController.clearBrowsingData(this);
                        finish();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));

        content.addView(buildSimpleCard(R.string.site_permissions_title, v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.site_permissions_title)
                    .setMessage(R.string.site_permissions_message)
                    .setPositiveButton(R.string.open_app_settings, (dialog, which) -> {
                        android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.close, null)
                    .show();
        }));

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
    }

    private String getSearchEngineLabel() {
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        String engine = prefs.getString(Prefs.KEY_SEARCH_ENGINE, "brave");
        switch (engine) {
            case "google":
                return getString(R.string.search_engine_google);
            case "duckduckgo":
                return getString(R.string.search_engine_duckduckgo);
            case "brave":
            default:
                return getString(R.string.search_engine_brave);
        }
    }

    private String getDownloadModeLabel() {
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        String mode = prefs.getString(Prefs.KEY_DOWNLOAD_MODE, Prefs.DOWNLOAD_MODE_NATIVE);
        return Prefs.DOWNLOAD_MODE_EXTERNAL.equals(mode)
                ? getString(R.string.download_manager_external)
                : getString(R.string.download_manager_native);
    }

    private MaterialCardView buildSearchEngineCard() {
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
        titleView.setText(R.string.search_engine_title);
        titleView.setTextSize(16);
        titleView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));

        searchEngineSubtitle = new TextView(this);
        searchEngineSubtitle.setText(getSearchEngineLabel());
        searchEngineSubtitle.setTextSize(13);
        searchEngineSubtitle.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        searchEngineSubtitle.setPadding(0, 4, 0, 0);

        inner.addView(titleView);
        inner.addView(searchEngineSubtitle);
        card.addView(inner);

        card.setOnClickListener(v -> {
            String[] engines = {
                    getString(R.string.search_engine_brave),
                    getString(R.string.search_engine_duckduckgo),
                    getString(R.string.search_engine_google)
            };
            new AlertDialog.Builder(this)
                    .setTitle(R.string.search_engine_title)
                    .setItems(engines, (dialog, which) -> {
                        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
                        String value = which == 0 ? "brave" : which == 1 ? "duckduckgo" : "google";
                        prefs.edit().putString(Prefs.KEY_SEARCH_ENGINE, value).apply();
                        searchEngineSubtitle.setText(getSearchEngineLabel());
                    })
                    .show();
        });

        return card;
    }

    private MaterialCardView buildDownloadModeCard() {
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
        titleView.setText(R.string.download_manager_title);
        titleView.setTextSize(16);
        titleView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));

        downloadModeSubtitle = new TextView(this);
        downloadModeSubtitle.setText(getDownloadModeLabel());
        downloadModeSubtitle.setTextSize(13);
        downloadModeSubtitle.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        downloadModeSubtitle.setPadding(0, 4, 0, 0);

        inner.addView(titleView);
        inner.addView(downloadModeSubtitle);
        card.addView(inner);

        card.setOnClickListener(v -> {
            String[] modes = {
                    getString(R.string.download_manager_native),
                    getString(R.string.download_manager_external)
            };
            new AlertDialog.Builder(this)
                    .setTitle(R.string.download_manager_title)
                    .setItems(modes, (dialog, which) -> {
                        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
                        String value = which == 0
                                ? Prefs.DOWNLOAD_MODE_NATIVE
                                : Prefs.DOWNLOAD_MODE_EXTERNAL;
                        prefs.edit().putString(Prefs.KEY_DOWNLOAD_MODE, value).apply();
                        downloadModeSubtitle.setText(getDownloadModeLabel());
                    })
                    .show();
        });

        return card;
    }

    private MaterialCardView buildSimpleCard(int titleRes,
                                             android.view.View.OnClickListener listener) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(16);
        card.setCardElevation(2);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);

        TextView titleView = new TextView(this);
        titleView.setText(titleRes);
        titleView.setTextSize(16);
        titleView.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
        titleView.setPadding(20, 20, 20, 20);

        card.addView(titleView);
        card.setOnClickListener(listener);

        return card;
    }
}
