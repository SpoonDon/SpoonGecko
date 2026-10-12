package com.spoongecko.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoView;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;

    private static final String FLAGS_PREFS = "spoongecko_flags";
    private static final String KEY_ASKED_BATTERY = "asked_battery";

    private GeckoView geckoView;
    private TabManager tabManager;

    private FrameLayout homeContainer;
    private HomeView homeView;

    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton reloadButton;
    private ImageButton menuButton;
    private TextView tabCountView;

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLogger.breadcrumb(this, "activity.onCreate begin");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        homeContainer = findViewById(R.id.home_container);
        urlBar = findViewById(R.id.url_bar);
        progressBar = findViewById(R.id.progress_bar);
        backButton = findViewById(R.id.btn_back);
        forwardButton = findViewById(R.id.btn_forward);
        reloadButton = findViewById(R.id.btn_reload);
        menuButton = findViewById(R.id.btn_menu);
        tabCountView = findViewById(R.id.tab_count);

        homeView = new HomeView(this, homeCallbacks);
        homeContainer.addView(homeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        GeckoRuntime runtime = GeckoApp.getRuntime();
        if (runtime == null) {
            Toast.makeText(this, R.string.error_runtime, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tabManager = new TabManager(this, geckoView, runtime, tabListener);

        wireToolbar();

        List<PersistedTab> persisted = SessionStore.loadTabs(this);
        if (persisted.isEmpty()) {
            tabManager.createTab(null);
            showHome();
        } else {
            tabManager.restoreFrom(persisted, SessionStore.loadCurrentIndex(this));
        }

        updateTabCount();
        KeepAliveService.start(this);
        requestNotificationPermissionIfNeeded();
        CrashLogger.breadcrumb(this, "activity.onCreate done");
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        maybeOfferBatteryExemption();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        String url = data.toString();
        // External intents open in a fresh tab so we don't clobber what's on screen.
        Tab t = tabManager.createTab(url);
        tabManager.switchTo(tabManager.getTabs().indexOf(t));
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveSession();
    }

    @Override
    protected void onDestroy() {
        CrashLogger.breadcrumb(this, "activity.onDestroy finishing=" + isFinishing());
        if (isFinishing() && tabManager != null) {
            tabManager.closeAll();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Tab t = tabManager.getCurrentTab();
        if (t != null && t.canGoBack) {
            t.session.goBack();
            return;
        }
        boolean onHome = homeContainer != null
                && homeContainer.getVisibility() == View.VISIBLE;
        if (!onHome) {
            showHome();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            CrashLogger.breadcrumb(this, "notifications granted=" + granted);
        }
    }

    private void saveSession() {
        if (tabManager == null) return;
        SessionStore.save(this,
                tabManager.snapshot(),
                Math.max(0, tabManager.getCurrentIndex()));
    }

    // ---------------------------------------------------------- Tab listener

    private final TabManager.Listener tabListener = new TabManager.Listener() {
        @Override public void onTabListChanged() { updateTabCount(); }

        @Override public void onCurrentTabChanged(Tab tab) {
            progressBar.setVisibility(View.GONE);
            if (tab == null || tab.url == null || tab.url.isEmpty()) {
                showHome();
            } else {
                hideHome();
                urlBar.setText(tab.url);
            }
        }

        @Override public void onPageStart(Tab tab, String url) {
            urlBar.setText(url);
            hideHome();
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override public void onPageStop(Tab tab, boolean success) {
            progressBar.setVisibility(View.GONE);
            if (success && isHttpOrHttps(tab.url)) {
                VisitStore.record(MainActivity.this, tab.title, tab.url);
            }
            saveSession();
        }

        @Override public void onProgress(Tab tab, int progress) {
            progressBar.setProgress(progress);
        }

        @Override public void onTitle(Tab tab, String title) {
            setTitle(TextUtils.isEmpty(title)
                    ? getString(R.string.app_name) : title);
        }

        @Override public void onCanGoBack(Tab tab, boolean value) {
            backButton.setEnabled(value);
            backButton.setAlpha(value ? 1f : 0.35f);
        }

        @Override public void onCanGoForward(Tab tab, boolean value) {
            forwardButton.setEnabled(value);
            forwardButton.setAlpha(value ? 1f : 0.35f);
        }
    };

        // ---------------------------------------------------------------- home

    private final HomeView.Callbacks homeCallbacks = new HomeView.Callbacks() {
        @Override public void onNavigate(String url) {
            if (TextUtils.isEmpty(url)) return;
            hideHome();
            tabManager.loadUrlInCurrentTab(url);
        }

        @Override public void onSearch(String query) {
            if (TextUtils.isEmpty(query)) return;
            onNavigate("https://duckduckgo.com/?q=" + Uri.encode(query));
        }

        @Override public void onAddQuickLink() { showQuickLinkEditor(null); }

        @Override public void onEditQuickLink(HomeStore.QuickLink link) {
            showQuickLinkEditor(link);
        }
    };

    private void showHome() {
        if (homeContainer == null) return;
        homeContainer.setVisibility(View.VISIBLE);
        if (homeView != null) {
            homeView.refresh();
            homeView.focusSearchBar();
        }
        urlBar.clearFocus();
        urlBar.setText("");
        progressBar.setVisibility(View.GONE);
        backButton.setEnabled(false);
        backButton.setAlpha(0.35f);
    }

    private void hideHome() {
        if (homeContainer != null) homeContainer.setVisibility(View.GONE);
    }

    private void showQuickLinkEditor(final HomeStore.QuickLink existing) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(8), pad, 0);

        final EditText titleInput = new EditText(this);
        titleInput.setHint("Title");
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) titleInput.setText(existing.title);
        col.addView(titleInput);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("https://example.com");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null) urlInput.setText(existing.url);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(8);
        col.addView(urlInput, urlLp);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add quick link" : "Edit quick link")
                .setView(col)
                .setPositiveButton("Save", (d, w) -> {
                    String title = titleInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.contains("://")) url = "https://" + url;
                    if (title.isEmpty()) {
                        try { title = Uri.parse(url).getHost(); } catch (Exception ignored) {}
                        if (title == null) title = url;
                    }
                    if (existing != null) HomeStore.removeQuickLink(this, existing.url);
                    HomeStore.addQuickLink(this, title, url);
                    if (homeView != null) homeView.refresh();
                })
                .setNegativeButton(android.R.string.cancel, null);

        if (existing != null) {
            b.setNeutralButton("Remove", (d, w) -> {
                HomeStore.removeQuickLink(this, existing.url);
                if (homeView != null) homeView.refresh();
            });
        }
        b.show();
    }

    // -------------------------------------------------------------- toolbar

    private void wireToolbar() {
        backButton.setOnClickListener(v -> {
            Tab t = tabManager.getCurrentTab();
            if (t == null) return;
            if (t.canGoBack) t.session.goBack();
            else showHome();
        });
        forwardButton.setOnClickListener(v -> {
            Tab t = tabManager.getCurrentTab();
            if (t != null) t.session.goForward();
        });
        reloadButton.setOnClickListener(v -> {
            if (homeContainer != null
                    && homeContainer.getVisibility() == View.VISIBLE) {
                if (homeView != null) homeView.refresh();
                return;
            }
            tabManager.reload();
        });
        menuButton.setOnClickListener(this::showMenu);
        tabCountView.setOnClickListener(v -> openTabSwitcher());

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });

        backButton.setEnabled(false); backButton.setAlpha(0.35f);
        forwardButton.setEnabled(false); forwardButton.setAlpha(0.35f);
    }

    private void updateTabCount() {
        if (tabCountView == null || tabManager == null) return;
        int n = tabManager.getTabCount();
        tabCountView.setText(n >= 100 ? "99+" : String.valueOf(n));
    }

    private void openTabSwitcher() {
        if (tabManager == null) return;
        TabSwitcherDialog.show(this, tabManager);
    }

    private void navigate(String raw) {
        if (TextUtils.isEmpty(raw)) return;
        hideKeyboard();
        urlBar.clearFocus();
        String normalized = normalize(raw);
        if (normalized == null) return;    // non-web scheme was handed off
        hideHome();
        tabManager.loadUrlInCurrentTab(normalized);
    }

    /**
     * Returns a web URL, or null if the input was a non-web scheme that we
     * dispatched to the OS instead of loading.
     */
    private String normalize(String raw) {
        String input = raw.trim();
        if (input.isEmpty()) return null;
        String lower = input.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("file:")) {
            return input;
        }
        if (lower.startsWith("magnet:") || lower.startsWith("tel:")
                || lower.startsWith("mailto:") || lower.startsWith("market:")
                || lower.startsWith("intent:")) {
            LinkSchemeHelper.dispatch(this, Uri.parse(input));
            return null;
        }
        boolean looksLikeHost = !input.contains(" ")
                && input.contains(".") && !input.endsWith(".");
        if (looksLikeHost) return "https://" + input;
        return "https://duckduckgo.com/?q=" + Uri.encode(input);
    }

    private static boolean isHttpOrHttps(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    // --------------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "New tab");
        popup.getMenu().add(0, 2, 1, "Tabs");
        popup.getMenu().add(0, 3, 2, "Close tab");
        popup.getMenu().add(0, 4, 3, R.string.menu_home);
        popup.getMenu().add(0, 5, 4, R.string.menu_share);
        popup.getMenu().add(0, 6, 5, R.string.menu_copy);
        popup.getMenu().add(0, 7, 6, R.string.menu_battery);
        popup.getMenu().add(0, 8, 7, R.string.menu_autostart);
        popup.getMenu().add(0, 9, 8, R.string.menu_about);
        popup.getMenu().add(0, 10, 9, "Show crash log");
        popup.getMenu().add(0, 11, 10, R.string.menu_exit);
        popup.setOnMenuItemClickListener(this::onMenuClick);
        popup.show();
    }

    private boolean onMenuClick(MenuItem item) {
        switch (item.getItemId()) {
            case 1: {
                Tab t = tabManager.createTab(null);
                tabManager.switchTo(tabManager.getTabs().indexOf(t));
                showHome();
                return true;
            }
            case 2: openTabSwitcher(); return true;
            case 3: tabManager.closeCurrentTab(); return true;
            case 4: showHome(); return true;
            case 5: shareCurrent(); return true;
            case 6: copyCurrent(); return true;
            case 7: OemHelper.requestIgnoreBatteryOptimizations(this); return true;
            case 8: OemHelper.openAutostartSettings(this); return true;
            case 9: showAbout(); return true;
            case 10: showCrashLog(); return true;
            case 11: exitApp(); return true;
            default: return false;
        }
    }

    private void shareCurrent() {
        Tab t = tabManager.getCurrentTab();
        String url = (t == null) ? "" : t.url;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)));
    }

    private void copyCurrent() {
        Tab t = tabManager.getCurrentTab();
        String url = (t == null) ? "" : t.url;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", url));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, versionName()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showCrashLog() {
        File f = CrashLogger.latestReportFile(this);
        if (f == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Crash log")
                    .setMessage("No crash report yet.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String text;
        try {
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = in.read(data);
                text = new String(data, 0, Math.max(read, 0), "UTF-8");
            }
        } catch (Exception e) {
            text = "Could not read " + f.getName() + ": " + e;
        }
        final String content = text;
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage(content)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("crash", content));
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exitApp() {
        saveSession();
        KeepAliveService.stop(this);
        finishAndRemoveTask();
    }

    private String versionName() {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------- survival UX

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    private void maybeOfferBatteryExemption() {
        if (OemHelper.isIgnoringBatteryOptimizations(this)) return;
        SharedPreferences flags = getSharedPreferences(FLAGS_PREFS, MODE_PRIVATE);
        if (flags.getBoolean(KEY_ASKED_BATTERY, false)) return;
        flags.edit().putBoolean(KEY_ASKED_BATTERY, true).apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_allow,
                        (d, w) -> OemHelper.requestIgnoreBatteryOptimizations(this))
                .setNegativeButton(R.string.battery_later, null)
                .show();
    }

    // --------------------------------------------------------------------- util

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
