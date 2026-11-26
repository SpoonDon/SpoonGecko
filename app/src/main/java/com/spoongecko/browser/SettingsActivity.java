package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.StorageController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Consolidated Browser Settings screen housing Search, Display, Translation,
 * Extensions, Downloads, Privacy/Security, Library access, and Clear Browsing Data.
 */
public class SettingsActivity extends Activity {

    private TextView valSearchEngine;
    private Switch switchSearchSuggestions;
    private Switch switchDefaultDesktop;
    private Switch switchPdfViewer;
    private TextView valTranslateTarget;
    private Switch switchTranslateOffers;
    private TextView valTranslateRules;
    private TextView valExtensions;
    private Switch switchExtAutoUpdate;
    private TextView valDownloads;
    private Switch switchAutoDownloadLinks;
    private TextView valAutoplay;
    private TextView valTracking;
    private TextView valDoh;
    private TextView valAbout;

    private HistoryStore historyStore;
    private BookmarkRepository bookmarkRepo;
    private HistoryDialog historyDialog;
    private BookmarksDialog bookmarksDialog;
    private TranslateDialogs translateDialogs;
    private final List<Dialog> activeDialogs = new ArrayList<>();

    private final ExtensionManager.Listener extListener = () -> runOnUiThread(this::refreshValues);
    private final DownloadEngine.Listener dlListener = () -> runOnUiThread(this::refreshValues);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ExtensionManager.get().initialize(this);
        PageTranslator.get().ensureContext(this);

        historyStore = new HistoryStore(this);
        bookmarkRepo = BookmarkRepository.get(this);
        historyDialog = new HistoryDialog(this, historyStore);
        bookmarksDialog = new BookmarksDialog(this, bookmarkRepo);
        translateDialogs = new TranslateDialogs(this, PageTranslator.get());

        findViewById(R.id.btn_settings_back).setOnClickListener(v -> finish());

        valSearchEngine = findViewById(R.id.val_search_engine);
        switchSearchSuggestions = findViewById(R.id.switch_search_suggestions);
        switchDefaultDesktop = findViewById(R.id.switch_default_desktop);
        switchPdfViewer = findViewById(R.id.switch_pdf_viewer);
        valTranslateTarget = findViewById(R.id.val_translate_target);
        switchTranslateOffers = findViewById(R.id.switch_translate_offers);
        valTranslateRules = findViewById(R.id.val_translate_rules);
        valExtensions = findViewById(R.id.val_extensions);
        switchExtAutoUpdate = findViewById(R.id.switch_ext_autoupdate);
        valDownloads = findViewById(R.id.val_downloads);
        switchAutoDownloadLinks = findViewById(R.id.switch_auto_download_links);
        valAutoplay = findViewById(R.id.val_autoplay);
        valTracking = findViewById(R.id.val_tracking);
        valDoh = findViewById(R.id.val_doh);
        valAbout = findViewById(R.id.val_about);

        wireRows();
        refreshValues();
        PageTranslator.get().whenRulesLoaded(() -> {
            if (!isFinishing() && !isDestroyed()) refreshValues();
        });
    }

    private void wireRows() {
        findViewById(R.id.row_search_engine).setOnClickListener(v -> pickSearchEngine());
        wireSwitchRow(R.id.row_search_suggestions, switchSearchSuggestions, enabled ->
                BrowserSettings.setSearchSuggestionsEnabled(this, enabled));

        wireSwitchRow(R.id.row_default_desktop, switchDefaultDesktop, enabled ->
                BrowserSettings.setDefaultDesktopMode(this, enabled));
        wireSwitchRow(R.id.row_pdf_viewer, switchPdfViewer, enabled ->
                BrowserSettings.setPdfViewerEnabled(this, enabled));

        findViewById(R.id.row_translate_target).setOnClickListener(v ->
                translateDialogs.showTargetLanguagePicker(this::refreshValues));
        wireSwitchRow(R.id.row_translate_offers, switchTranslateOffers, enabled ->
                PageTranslator.get().setOffersEnabled(enabled));
        findViewById(R.id.row_translate_rules).setOnClickListener(v ->
                translateDialogs.showRules(this::refreshValues));

        findViewById(R.id.row_extensions).setOnClickListener(v ->
                startActivity(new Intent(this, ExtensionsActivity.class)));
        wireSwitchRow(R.id.row_ext_autoupdate, switchExtAutoUpdate, enabled ->
                ExtensionManager.get().setAutoUpdateEnabled(enabled));

        findViewById(R.id.row_downloads).setOnClickListener(v ->
                startActivity(new Intent(this, DownloadsActivity.class)));
        wireSwitchRow(R.id.row_auto_download_links, switchAutoDownloadLinks, enabled ->
                BrowserSettings.setAutoDownloadLinks(this, enabled));
        findViewById(R.id.row_downloads_folder).setOnClickListener(v ->
                DownloadEngine.openDownloadsFolder(this));

        findViewById(R.id.row_autoplay).setOnClickListener(v -> pickAutoplayPolicy());
        findViewById(R.id.row_tracking).setOnClickListener(v -> pickTrackingLevel());
        findViewById(R.id.row_doh).setOnClickListener(v -> showDnsSettingsDialog());

        findViewById(R.id.row_bookmarks).setOnClickListener(v ->
                bookmarksDialog.show(null, null, this::openUrlInBrowser));
        findViewById(R.id.row_history).setOnClickListener(v ->
                historyDialog.show(new HistoryDialog.Actions() {
                    @Override public void onOpenDefault(String url) {
                        openHistoryUrl(url, MainActivity.OPEN_MODE_HISTORY_DEFAULT);
                    }
                    @Override public void onOpenInNewTab(String url) {
                        openHistoryUrl(url, MainActivity.OPEN_MODE_NEW_TAB);
                    }
                    @Override public void onOpenInNewIncognitoTab(String url) {
                        openHistoryUrl(url, MainActivity.OPEN_MODE_NEW_INCOGNITO_TAB);
                    }
                    @Override public void onOpenInNewWindow(String url) {
                        openUrlInNewWindow(false, url);
                    }
                    @Override public void onOpenInNewIncognitoWindow(String url) {
                        openUrlInNewWindow(true, url);
                    }
                }));
        findViewById(R.id.row_clear_data).setOnClickListener(v -> showClearDataDialog());

        findViewById(R.id.row_app_permissions).setOnClickListener(v -> openAppPermissions());
    }

    private void wireSwitchRow(int rowId, Switch sw, Consumer<Boolean> onChanged) {
        findViewById(rowId).setOnClickListener(v -> sw.toggle());
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            onChanged.accept(isChecked);
            refreshValues();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ExtensionManager.get().addListener(extListener);
        DownloadEngine.get(this).addListener(dlListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    @Override
    protected void onStop() {
        ExtensionManager.get().removeListener(extListener);
        DownloadEngine.get(this).removeListener(dlListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        for (Dialog d : new ArrayList<>(activeDialogs)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {}
        }
        activeDialogs.clear();
        if (translateDialogs != null) translateDialogs.dismissAll();
        if (historyDialog != null) historyDialog.dismiss();
        if (bookmarksDialog != null) bookmarksDialog.dismiss();
        if (historyStore != null) historyStore.close();
        super.onDestroy();
    }

    private void refreshValues() {
        if (isFinishing() || isDestroyed()) return;

        valSearchEngine.setText(BrowserSettings.getSearchEngine(this).displayName);
        setSwitchSilently(switchSearchSuggestions,
                BrowserSettings.isSearchSuggestionsEnabled(this),
                enabled -> BrowserSettings.setSearchSuggestionsEnabled(this, enabled));

        setSwitchSilently(switchDefaultDesktop,
                BrowserSettings.isDefaultDesktopMode(this),
                enabled -> BrowserSettings.setDefaultDesktopMode(this, enabled));
        setSwitchSilently(switchPdfViewer,
                BrowserSettings.isPdfViewerEnabled(this),
                enabled -> BrowserSettings.setPdfViewerEnabled(this, enabled));

        final String targetCode = PageTranslator.get().targetLanguage();
        valTranslateTarget.setText(targetCode == null ? "System default"
                : PageTranslator.get().languageName(targetCode));
        setSwitchSilently(switchTranslateOffers,
                PageTranslator.get().offersEnabled(),
                enabled -> PageTranslator.get().setOffersEnabled(enabled));

        final int rulesCount = PageTranslator.get().customRules().size();
        valTranslateRules.setText(rulesCount == 0
                ? "No custom language rules"
                : rulesCount + (rulesCount == 1 ? " custom language rule" : " custom language rules"));

        final int extCount = countUserExtensions();
        valExtensions.setText(extCount == 0
                ? "No extensions installed"
                : extCount + (extCount == 1 ? " extension installed" : " extensions installed"));
        setSwitchSilently(switchExtAutoUpdate,
                ExtensionManager.get().isAutoUpdateEnabled(),
                enabled -> ExtensionManager.get().setAutoUpdateEnabled(enabled));

        final DownloadEngine dl = DownloadEngine.get(this);
        final int activeDl = dl.getActiveCount();
        final int totalDl = dl.getEntries().size();
        if (activeDl > 0) {
            valDownloads.setText(activeDl + " active \u00b7 " + totalDl + " total");
        } else if (totalDl > 0) {
            valDownloads.setText(totalDl + (totalDl == 1 ? " file recorded" : " files recorded"));
        } else {
            valDownloads.setText("Manage active and completed downloads");
        }
        setSwitchSilently(switchAutoDownloadLinks,
                BrowserSettings.isAutoDownloadLinks(this),
                enabled -> BrowserSettings.setAutoDownloadLinks(this, enabled));

        valAutoplay.setText(BrowserSettings.getAutoplayPolicy(this).displayName);
        valTracking.setText(BrowserSettings.getTrackingLevel(this).displayName);
        valDoh.setText(BrowserSettings.dohSummary(this));

        valAbout.setText(versionLabel());
    }

    private void setSwitchSilently(Switch sw, boolean checked, Consumer<Boolean> onChanged) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            onChanged.accept(isChecked);
            refreshValues();
        });
    }

    private int countUserExtensions() {
        int count = 0;
        for (org.mozilla.geckoview.WebExtension ext : ExtensionManager.get().getInstalled()) {
            if (ext != null && !ext.isBuiltIn) count++;
        }
        return count;
    }

    private String versionLabel() {
        try {
            final PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            final String vn = pi.versionName == null ? "1.0" : pi.versionName;
            return "Version " + vn + " (build " + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "SpoonGecko";
        }
    }

    // ------------------------------------------------------------------
    // Option pickers
    // ------------------------------------------------------------------

    private void pickSearchEngine() {
        final BrowserSettings.SearchEngine[] engines = BrowserSettings.SearchEngine.values();
        final BrowserSettings.SearchEngine current = BrowserSettings.getSearchEngine(this);
        final String[] labels = new String[engines.length];
        int selected = 0;
        for (int i = 0; i < engines.length; i++) {
            labels[i] = engines[i].displayName;
            if (engines[i] == current) selected = i;
        }
        showGlassOptionDialog("Search engine", labels, selected, idx -> {
            BrowserSettings.setSearchEngine(this, engines[idx]);
            refreshValues();
        });
    }

    private void pickAutoplayPolicy() {
        final BrowserSettings.AutoplayPolicy[] policies = BrowserSettings.AutoplayPolicy.values();
        final BrowserSettings.AutoplayPolicy current = BrowserSettings.getAutoplayPolicy(this);
        final String[] labels = new String[policies.length];
        int selected = 0;
        for (int i = 0; i < policies.length; i++) {
            labels[i] = policies[i].displayName;
            if (policies[i] == current) selected = i;
        }
        showGlassOptionDialog("Media autoplay", labels, selected, idx -> {
            BrowserSettings.setAutoplayPolicy(this, policies[idx]);
            refreshValues();
        });
    }

    private void pickTrackingLevel() {
        final BrowserSettings.TrackingLevel[] levels = BrowserSettings.TrackingLevel.values();
        final BrowserSettings.TrackingLevel current = BrowserSettings.getTrackingLevel(this);
        final String[] labels = new String[levels.length];
        int selected = 0;
        for (int i = 0; i < levels.length; i++) {
            labels[i] = levels[i].displayName;
            if (levels[i] == current) selected = i;
        }
        showGlassOptionDialog("Enhanced Tracking Protection", labels, selected, idx -> {
            BrowserSettings.setTrackingLevel(this, levels[idx]);
            BrowserSettings.applyRuntimeSettings(this, ExtensionManager.ensureRuntime(this));
            refreshValues();
        });
    }

    private void showDnsSettingsDialog() {
        final LinearLayout content = dialogContent(20);
        content.addView(text("Custom DNS (DNS over HTTPS)", 0xFFFFFFFF, 17, true));
        final TextView subtitle = text(
                "Encrypt domain lookups with a preset or custom DoH resolver.",
                0x99FFFFFF, 12, false);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        content.addView(subtitle);

        final BrowserSettings.DohMode[] selectedMode = {BrowserSettings.getDohMode(this)};
        final BrowserSettings.DnsProvider[] selectedProvider = {BrowserSettings.getDnsProvider(this)};
        final boolean[] allowLocal = {BrowserSettings.isDnsAllowLocalEnabled(this)};

        final TextView modeHeader = text("Protection mode", 0xCCFFFFFF, 12, true);
        modeHeader.setPadding(0, dp(4), 0, dp(4));
        content.addView(modeHeader);

        final BrowserSettings.DohMode[] modes = BrowserSettings.DohMode.values();
        final TextView[] modeChips = new TextView[modes.length];
        for (int i = 0; i < modes.length; i++) {
            final TextView chip = text("", 0xFFFFFFFF, 14, false);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(14), dp(11), dp(14), dp(11));
            chip.setClickable(true);
            chip.setFocusable(true);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            content.addView(chip, lp);
            modeChips[i] = chip;
        }

        final LinearLayout resolverSection = new LinearLayout(this);
        resolverSection.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        content.addView(resolverSection, rlp);

        final TextView providerHeader = text("DNS provider", 0xCCFFFFFF, 12, true);
        providerHeader.setPadding(0, dp(2), 0, dp(4));
        resolverSection.addView(providerHeader);

        final BrowserSettings.DnsProvider[] providers = BrowserSettings.DnsProvider.values();
        final TextView[] providerChips = new TextView[providers.length];
        for (int i = 0; i < providers.length; i++) {
            final TextView chip = text("", 0xFFFFFFFF, 14, false);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(14), dp(11), dp(14), dp(11));
            chip.setClickable(true);
            chip.setFocusable(true);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            resolverSection.addView(chip, lp);
            providerChips[i] = chip;
        }

        final TextView endpointPreview = text("", 0x99FFFFFF, 11, false);
        endpointPreview.setPadding(dp(2), dp(8), dp(2), 0);
        resolverSection.addView(endpointPreview);

        final LinearLayout customUrlContainer = new LinearLayout(this);
        customUrlContainer.setOrientation(LinearLayout.VERTICAL);
        resolverSection.addView(customUrlContainer);

        final TextView customUrlLabel = text("Custom DoH server URL", 0xCCFFFFFF, 12, true);
        customUrlLabel.setPadding(0, dp(10), 0, dp(4));
        customUrlContainer.addView(customUrlLabel);

        final EditText customUrlInput = new EditText(this);
        customUrlInput.setHint("https://dns.example.com/dns-query");
        customUrlInput.setText(BrowserSettings.getCustomDohUri(this));
        customUrlInput.setTextColor(0xFFFFFFFF);
        customUrlInput.setHintTextColor(0x66FFFFFF);
        customUrlInput.setTextSize(13);
        customUrlInput.setSingleLine(true);
        customUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        customUrlInput.setBackgroundResource(R.drawable.bg_glass_input);
        customUrlInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        customUrlContainer.addView(customUrlInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView bootstrapLabel = text("Bootstrap IP (optional)", 0xCCFFFFFF, 12, true);
        bootstrapLabel.setPadding(0, dp(10), 0, dp(4));
        resolverSection.addView(bootstrapLabel);

        final EditText bootstrapInput = new EditText(this);
        bootstrapInput.setText(BrowserSettings.getDnsBootstrapIp(this));
        bootstrapInput.setTextColor(0xFFFFFFFF);
        bootstrapInput.setHintTextColor(0x66FFFFFF);
        bootstrapInput.setTextSize(13);
        bootstrapInput.setSingleLine(true);
        bootstrapInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        bootstrapInput.setBackgroundResource(R.drawable.bg_glass_input);
        bootstrapInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        resolverSection.addView(bootstrapInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final CheckBox cbLocal = new CheckBox(this);
        cbLocal.setText("Bypass DoH for local network / router addresses");
        cbLocal.setTextColor(0xFFFFFFFF);
        cbLocal.setTextSize(13);
        cbLocal.setChecked(allowLocal[0]);
        cbLocal.setPadding(dp(2), dp(10), dp(2), dp(4));
        cbLocal.setOnCheckedChangeListener((b, checked) -> allowLocal[0] = checked);
        resolverSection.addView(cbLocal);

        final Runnable updateUi = () -> {
            for (int i = 0; i < modes.length; i++) {
                final boolean sel = modes[i] == selectedMode[0];
                modeChips[i].setText((sel ? "\u2713  " : "") + modes[i].displayName);
                modeChips[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                modeChips[i].setBackgroundResource(sel ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            }
            resolverSection.setVisibility(
                    selectedMode[0] == BrowserSettings.DohMode.OFF ? View.GONE : View.VISIBLE);

            for (int i = 0; i < providers.length; i++) {
                final boolean sel = providers[i] == selectedProvider[0];
                providerChips[i].setText((sel ? "\u2713  " : "") + providers[i].displayName);
                providerChips[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                providerChips[i].setBackgroundResource(sel ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            }

            final boolean isCustom = selectedProvider[0] == BrowserSettings.DnsProvider.CUSTOM;
            customUrlContainer.setVisibility(isCustom ? View.VISIBLE : View.GONE);

            if (isCustom) {
                endpointPreview.setVisibility(View.GONE);
                bootstrapInput.setHint("e.g. 1.1.1.1 or 2606:4700:4700::1111");
            } else if (selectedProvider[0].uri.isEmpty()) {
                endpointPreview.setVisibility(View.VISIBLE);
                endpointPreview.setText("Endpoint: Browser built-in default resolver");
                bootstrapInput.setHint("Optional IP address");
            } else {
                endpointPreview.setVisibility(View.VISIBLE);
                endpointPreview.setText("Endpoint: " + selectedProvider[0].uri);
                bootstrapInput.setHint("Default: " + selectedProvider[0].defaultBootstrapIp);
            }
        };

        for (int i = 0; i < modes.length; i++) {
            final BrowserSettings.DohMode m = modes[i];
            modeChips[i].setOnClickListener(v -> {
                selectedMode[0] = m;
                updateUi.run();
            });
        }
        for (int i = 0; i < providers.length; i++) {
            final BrowserSettings.DnsProvider p = providers[i];
            providerChips[i].setOnClickListener(v -> {
                selectedProvider[0] = p;
                updateUi.run();
            });
        }
        updateUi.run();

        final LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);

        final TextView resetBtn = button("Reset", false);
        final LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        rblp.setMarginEnd(dp(4));
        bottomRow.addView(resetBtn, rblp);

        final TextView cancelBtn = button("Cancel", false);
        final LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        cblp.setMarginStart(dp(4));
        cblp.setMarginEnd(dp(4));
        bottomRow.addView(cancelBtn, cblp);

        final TextView saveBtn = button("Save", false);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        final LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sblp.setMarginStart(dp(4));
        bottomRow.addView(saveBtn, sblp);

        content.addView(bottomRow, blp);

        final AlertDialog dialog = glassDialog(new ScrollView(this), content);
        resetBtn.setOnClickListener(v -> {
            selectedMode[0] = BrowserSettings.DohMode.FIRST;
            selectedProvider[0] = BrowserSettings.DnsProvider.DEFAULT;
            customUrlInput.setText("");
            bootstrapInput.setText("");
            allowLocal[0] = true;
            cbLocal.setChecked(true);
            updateUi.run();
        });
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        saveBtn.setOnClickListener(v -> {
            final String customUrl = customUrlInput.getText().toString().trim();
            final String bootstrap = bootstrapInput.getText().toString().trim();
            if (selectedMode[0] != BrowserSettings.DohMode.OFF
                    && selectedProvider[0] == BrowserSettings.DnsProvider.CUSTOM
                    && !BrowserSettings.isValidDohUrl(customUrl)) {
                Toast.makeText(this, "Enter a valid https:// DoH server URL", Toast.LENGTH_SHORT).show();
                customUrlInput.requestFocus();
                return;
            }
            if (!bootstrap.isEmpty() && !BrowserSettings.isValidBootstrapIp(bootstrap)) {
                Toast.makeText(this, "Enter a valid IPv4 or IPv6 bootstrap address", Toast.LENGTH_SHORT).show();
                bootstrapInput.requestFocus();
                return;
            }
            BrowserSettings.setDnsConfig(this, selectedMode[0], selectedProvider[0],
                    customUrl, bootstrap, allowLocal[0]);
            BrowserSettings.applyRuntimeSettings(this, ExtensionManager.ensureRuntime(this));
            refreshValues();
            dialog.dismiss();
            Toast.makeText(this, "DNS settings updated", Toast.LENGTH_SHORT).show();
        });
    }

    private void showGlassOptionDialog(String title, String[] options, int selectedIdx,
                                       Consumer<Integer> onPicked) {
        final LinearLayout content = dialogContent(20);
        content.addView(text(title, 0xFFFFFFFF, 17, true));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            final boolean isSelected = i == selectedIdx;
            final TextView row = text((isSelected ? "\u2713  " : "") + options[i],
                    0xFFFFFFFF, 14, isSelected);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackgroundResource(isSelected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            row.setClickable(true);
            row.setFocusable(true);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            row.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                onPicked.accept(index);
            });
            content.addView(row, lp);
        }

        final TextView cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> dialogRef[0].dismiss());
        content.addView(cancel);
        dialogRef[0] = glassDialog(new ScrollView(this), content);
    }

    // ------------------------------------------------------------------
    // Clear browsing data
    // ------------------------------------------------------------------

    private void showClearDataDialog() {
        final LinearLayout content = dialogContent(20);
        content.addView(text("Clear browsing data", 0xFFFFFFFF, 17, true));
        final TextView sub = text("Choose which data to remove from this device.",
                0x99FFFFFF, 12, false);
        sub.setPadding(0, dp(4), 0, dp(8));
        content.addView(sub);

        final CheckBox cbHistory = addCheckBox(content, "Browsing history", true);
        final CheckBox cbCache = addCheckBox(content, "Cached web pages and images", true);
        final CheckBox cbSiteData = addCheckBox(content, "Cookies and site data", false);
        final CheckBox cbDownloads = addCheckBox(content, "Finished download records", false);
        final CheckBox cbBookmarks = addCheckBox(content, "Saved bookmarks", false);

        final TextView clearBtn = button("Clear selected data", true);
        final TextView cancelBtn = button("Cancel", false);
        content.addView(clearBtn);
        content.addView(cancelBtn);

        final AlertDialog dialog = glassDialog(new ScrollView(this), content);
        clearBtn.setOnClickListener(v -> {
            final boolean delHistory = cbHistory.isChecked();
            final boolean delCache = cbCache.isChecked();
            final boolean delSiteData = cbSiteData.isChecked();
            final boolean delDownloads = cbDownloads.isChecked();
            final boolean delBookmarks = cbBookmarks.isChecked();

            if (!delHistory && !delCache && !delSiteData && !delDownloads && !delBookmarks) {
                Toast.makeText(this, "Select at least one item", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();

            if (delHistory) historyStore.clearAsync();
            if (delBookmarks) bookmarkRepo.clearAll(null);
            if (delDownloads) DownloadEngine.get(this).clearFinished();

            long clearFlags = 0L;
            if (delCache) clearFlags |= StorageController.ClearFlags.ALL_CACHES;
            if (delSiteData) {
                clearFlags |= StorageController.ClearFlags.COOKIES
                        | StorageController.ClearFlags.SITE_DATA
                        | StorageController.ClearFlags.SITE_SETTINGS;
            }
            if (clearFlags != 0L) {
                try {
                    final GeckoRuntime rt = ExtensionManager.ensureRuntime(this);
                    rt.getStorageController().clearData(clearFlags);
                } catch (RuntimeException ignored) {}
            }
            refreshValues();
            Toast.makeText(this, "Selected browsing data cleared", Toast.LENGTH_SHORT).show();
        });
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
    }

    private CheckBox addCheckBox(LinearLayout parent, String label, boolean checked) {
        final CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(0xFFFFFFFF);
        cb.setTextSize(14);
        cb.setChecked(checked);
        cb.setPadding(dp(4), dp(8), dp(4), dp(8));
        parent.addView(cb);
        return cb;
    }

    // ------------------------------------------------------------------
    // External navigation helpers
    // ------------------------------------------------------------------

    private void openUrlInBrowser(String url) {
        openHistoryUrl(url, null);
    }

    private void openHistoryUrl(String url, String openMode) {
        if (url == null || url.trim().isEmpty()) return;
        final String trimmed = url.trim();
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            final Intent intent = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(trimmed))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (openMode != null) {
                intent.putExtra(MainActivity.EXTRA_OPEN_MODE, openMode);
            }
            startActivity(intent);
            finish();
        } else {
            ExtensionManager.get().openInBrowserTab(trimmed);
            final Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        }
    }

    private void openUrlInNewWindow(boolean incognito, String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String trimmed = url.trim();
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            openHistoryUrl(trimmed, MainActivity.OPEN_MODE_NEW_TAB);
            return;
        }
        final Intent intent = new Intent(this, BrowserWindowActivity.class);
        intent.putExtra(MainActivity.EXTRA_START_PRIVATE, incognito);
        intent.putExtra(MainActivity.EXTRA_INITIAL_URL, trimmed);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try {
            startActivity(intent);
            finish();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't open a new window", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppPermissions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Android app settings", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Glass dialog helpers
    // ------------------------------------------------------------------

    private void trackDialog(Dialog d) {
        activeDialogs.add(d);
        d.setOnDismissListener(x -> activeDialogs.remove(d));
    }

    private LinearLayout dialogContent(int padDp) {
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_glass_dialog);
        final int pad = dp(padDp);
        content.setPadding(pad, pad, pad, dp(12));
        return content;
    }

    private TextView text(String s, int color, float sizeSp, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean red) {
        final TextView b = text(label, red ? 0xFFFF8888 : 0xFFFFFFFF, 13, false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(red ? R.drawable.bg_glass_button_red : R.drawable.bg_glass_button);
        b.setClickable(true);
        b.setFocusable(true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private AlertDialog glassDialog(ScrollView scroll, View content) {
        scroll.addView(content);
        final AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        trackDialog(dialog);
        dialog.show();
        return dialog;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
