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
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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
    private SwipeRefreshLayout swipeRefresh;

    private FrameLayout homeContainer;
    private HomeView homeView;

    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton forwardButton;
    private ImageButton restoreButton;
    private ImageButton menuButton;
    private TextView tabCountView;

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLogger.breadcrumb(this, "activity.onCreate begin");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        homeContainer = findViewById(R.id.home_container);
        urlBar = findViewById(R.id.url_bar);
        progressBar = findViewById(R.id.progress_bar);
        forwardButton = findViewById(R.id.btn_forward);
        restoreButton = findViewById(R.id.btn_restore);
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
        wirePullToRefresh();

        List<PersistedTab> persisted = SessionStore.loadTabs(this);
        if (persisted.isEmpty()) {
            // createTab() fires onCurrentTabChanged, which calls showHome(true).
            // No explicit showHome() here — it used to be called twice.
            tabManager.createTab(null);
        } else {
            tabManager.restoreFrom(persisted, SessionStore.loadCurrentIndex(this));
        }

        updateTabBadge();
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
        // If the runtime failed to initialise, onCreate() finished us but a
        // queued ACTION_VIEW intent can still be delivered. tabManager is null
        // in that path.
        if (tabManager == null) return;
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        Tab t = tabManager.createTab(data.toString());
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
        if (isFinishing() && tabManager != null) tabManager.closeAll();
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
        @Override public void onTabListChanged() { updateTabBadge(); }

        @Override public void onCurrentTabChanged(Tab tab) {
            progressBar.setVisibility(View.GONE);
            updateTabBadge();
            if (tab == null || tab.url == null || tab.url.isEmpty()) {
                // An empty tab is a "new tab" state — the user expects the
                // keyboard ready.
                showHome(true);
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
            if (swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false);
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

        @Override public void onCanGoForward(Tab tab, boolean value) {
            forwardButton.setEnabled(value);
            forwardButton.setAlpha(value ? 1f : 0.35f);
        }
    };

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
    };

    /** Show home without focusing the search bar (Back from a page, menu Home). */
    private void showHome() {
        showHome(false);
    }

    /**
     * Show home, optionally focusing the search bar.
     *
     * Focusing the bar pops the soft keyboard. That's the right behaviour when
     * the user has just created an empty tab, and the wrong behaviour when
     * they pressed Back out of a loaded page or tapped menu → Home.
     */
    private void showHome(boolean focus) {
        if (homeContainer == null) return;
        homeContainer.setVisibility(View.VISIBLE);
        if (homeView != null) {
            homeView.refresh();
            if (focus) homeView.focusSearchBar();
        }
        urlBar.clearFocus();
        urlBar.setText("");
        progressBar.setVisibility(View.GONE);
    }

    private void hideHome() {
        if (homeContainer != null) homeContainer.setVisibility(View.GONE);
    }

    // -------------------------------------------------------------- toolbar

    private void wireToolbar() {
        forwardButton.setOnClickListener(v -> {
            Tab t = tabManager.getCurrentTab();
            if (t != null) t.session.goForward();
        });

        restoreButton.setOnClickListener(v -> {
            if (!tabManager.restoreLastClosed()) {
                Toast.makeText(this, "No closed tabs to restore",
                        Toast.LENGTH_SHORT).show();
            }
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

        forwardButton.setEnabled(false); forwardButton.setAlpha(0.35f);
    }

    /** Called from onTabListChanged + onCurrentTabChanged. */
    private void updateTabBadge() {
        if (tabCountView == null || tabManager == null) return;
        int n = tabManager.getTabCount();
        int cur = tabManager.getCurrentIndex();
        if (n <= 0 || cur < 0) {
            tabCountView.setText("0");
            return;
        }
        tabCountView.setText((cur + 1) + "/" + n);
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
        if (normalized == null) return;
        hideHome();
        tabManager.loadUrlInCurrentTab(normalized);
    }

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

    // ------------------------------------------------------------- swipe

    private void wirePullToRefresh() {
        // Tint spinner to match accent
        try { swipeRefresh.setColorSchemeColors(0xFF4D8DFF); } catch (Exception ignored) {}

        // Gate pull-to-refresh on the ACTUAL page scroll state reported by JS.
        // Returning true means "child can scroll up" → SwipeRefreshLayout
        // won't steal the touch; the page handles it.
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> {
            if (homeContainer.getVisibility() == View.VISIBLE) return true;
            if (tabManager == null) return true;
            return !tabManager.isCurrentTabAtTop();
        });

        swipeRefresh.setOnRefreshListener(() -> {
            if (homeContainer.getVisibility() == View.VISIBLE) {
                swipeRefresh.setRefreshing(false);
                return;
            }
            tabManager.reload();
            // Safety net — clear if onPageStop never fires (error page).
            swipeRefresh.postDelayed(() -> {
                if (swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false);
            }, 3000);
        });
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Reload");
        popup.getMenu().add(0, 2, 1, "New tab");
        popup.getMenu().add(0, 3, 2, "Tabs");
        popup.getMenu().add(0, 4, 3, "Close tab");
        popup.getMenu().add(0, 5, 4, R.string.menu_home);
        popup.getMenu().add(0, 6, 5, R.string.menu_share);
        popup.getMenu().add(0, 7, 6, R.string.menu_copy);
        popup.getMenu().add(0, 8, 7, R.string.menu_battery);
        popup.getMenu().add(0, 9, 8, R.string.menu_autostart);
        popup.getMenu().add(0, 10, 9, R.string.menu_about);
        popup.getMenu().add(0, 11, 10, "Show crash log");
        popup.getMenu().add(0, 12, 11, R.string.menu_exit);
        popup.setOnMenuItemClickListener(this::onMenuClick);
        popup.show();
    }

    private boolean onMenuClick(MenuItem item) {
        switch (item.getItemId()) {
            case 1: tabManager.reload(); return true;
            case 2: {
                Tab t = tabManager.createTab(null);
                tabManager.switchTo(tabManager.getTabs().indexOf(t));
                showHome(true);   // new tab → focus the bar
                return true;
            }
            case 3: openTabSwitcher(); return true;
            case 4: tabManager.closeCurrentTab(); return true;
            case 5: showHome(); return true;   // menu Home → no focus
            case 6: shareCurrent(); return true;
            case 7: copyCurrent(); return true;
            case 8: OemHelper.requestIgnoreBatteryOptimizations(this); return true;
            case 9: OemHelper.openAutostartSettings(this); return true;
            case 10: showAbout(); return true;
            case 11: showCrashLog(); return true;
            case 12: exitApp(); return true;
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
}
