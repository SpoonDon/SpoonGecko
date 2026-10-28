package com.spoongecko.browser;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import org.mozilla.geckoview.CompositorController;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity
        implements ComponentCallbacks2, TabManager.Listener,
                   BrowserDelegates.Host, PopupsController.Host,
                   SuggestionsController.Listener {

    private static final String TAG = "SpoonGecko";
    private static final String BRAVE_SEARCH_URL = "https://search.brave.com/search?q=";
    private static final int REQ_ANDROID_PERMS = 0x5151;

    private static GeckoRuntime sRuntime;
    private static List<GeckoSession.SessionState> sPersistedStates;
    private static int sPersistedActiveIndex = 0;

    private TabManager tabManager;
    private GeckoView geckoView;
    private HistoryStore history;

    private EditText urlBar;
    private EditText searchBar;
    private FrameLayout tabButton;
    private TextView tabCountText;
    private ImageButton forwardButton;

    private ScreenController screen;
    private SuggestionsController suggestions;
    private PopupsController popups;
    private BrowserDialogs dialogs;
    private BrowserDelegates delegates;

    private GeckoSession.PermissionDelegate.Callback pendingPermissionCallback;

    private static synchronized GeckoRuntime ensureRuntime(Activity activity) {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                            .build())
                    .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                    .fissionEnabled(true)
                    .remoteDebuggingEnabled(false)
                    .build();
            sRuntime = GeckoRuntime.create(activity.getApplicationContext(), settings);
        }
        return sRuntime;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Window decorations must be adjusted before content is attached.
        new ScreenController(this).makeImmersive();
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);
        urlBar = findViewById(R.id.url_bar);
        searchBar = findViewById(R.id.search_bar);
        tabButton = findViewById(R.id.tab_button);
        tabCountText = findViewById(R.id.tab_count_text);
        forwardButton = findViewById(R.id.forward_button);
        ImageButton menuButton = findViewById(R.id.menu_button);
        ListView suggestionsList = findViewById(R.id.suggestions_list);

        geckoView.setBackgroundColor(android.graphics.Color.BLACK);
        history = new HistoryStore(this);

        screen = new ScreenController(this);
        screen.installFlashGuard();
        screen.setupWindowInsets();
        new Handler(Looper.getMainLooper()).postDelayed(screen::removeFlashGuard, 1500);

        suggestions = new SuggestionsController(this, urlBar, suggestionsList, history);
        suggestions.setListener(this);

        tabManager = new TabManager();
        tabManager.setListener(this);

        popups = new PopupsController(this, geckoView, menuButton, this);
        dialogs = new BrowserDialogs(this, tabManager, history);
        delegates = new BrowserDelegates(this, this);

        setupBackHandler();
        setupInputHandlers();

        sRuntime = ensureRuntime(this);
        restoreTabs(savedInstanceState);

        updateTabCount();
    }

    // ------------------------------------------------------------------
    // Input wiring
    // ------------------------------------------------------------------
    private void setupInputHandlers() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String input = urlBar.getText().toString().trim();
                if (!TextUtils.isEmpty(input)) navigateFromInput(input);
                urlBar.clearFocus();
                screen.hideKeyboard();
                suggestions.hide();
                return true;
            }
            return false;
        });

        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = searchBar.getText().toString().trim();
                if (!TextUtils.isEmpty(q)) {
                    navigateToUrl(BRAVE_SEARCH_URL + urlEncode(q));
                    searchBar.setText("");
                    searchBar.clearFocus();
                }
                screen.hideKeyboard();
                return true;
            }
            return false;
        });

        tabButton.setOnClickListener(v -> dialogs.showTabList(
                idx -> tabManager.switchTo(idx), this::newTab));

        forwardButton.setOnClickListener(v -> {
            Tab t = tabManager.getActiveTab();
            if (t != null && t.canGoForward) t.session.goForward();
        });
        findViewById(R.id.menu_button).setOnClickListener(v -> popups.showMenu());
    }

    private void setupBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        this::handleBackAction);
            } catch (Exception e) { Log.w(TAG, "register back callback failed", e); }
        }
    }

    private void handleBackAction() {
        if (suggestions != null) {
            // If suggestions are visible, close them first.
            // isHomePanelVisible() covers "no page loaded".
        }
        Tab t = tabManager.getActiveTab();
        if (t != null && t.canGoBack) { t.session.goBack(); return; }
        if (!screen.isHomePanelVisible()) { goHome(); return; }
        showExitConfirmation();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBackAction(); }

    // ------------------------------------------------------------------
    // Tab lifecycle
    // ------------------------------------------------------------------
    private Tab addNewTab(GeckoSession.SessionState state) {
        Tab tab = (state != null) ? tabManager.createTabWithState(state)
                                  : tabManager.createTab();
        delegates.attach(tab);
        tab.session.open(sRuntime);
        tabManager.notifyTabsChanged();
        tabManager.notifyActiveChanged();
        return tab;
    }

    private void restoreTabs(Bundle savedInstanceState) {
        if (sPersistedStates != null && !sPersistedStates.isEmpty()) {
            for (GeckoSession.SessionState s : sPersistedStates) addNewTab(s);
            tabManager.switchTo(Math.min(sPersistedActiveIndex, tabManager.count() - 1));
            sPersistedStates = null;
            screen.showHomePanel(false);
            return;
        }
        TabPersistence.Restored disk = TabPersistence.load(this);
        if (!disk.states.isEmpty()) {
            for (GeckoSession.SessionState s : disk.states) addNewTab(s);
            tabManager.switchTo(Math.min(disk.activeIndex, tabManager.count() - 1));
            screen.showHomePanel(false);
            return;
        }
        addNewTab(null);
        screen.showHomePanel(true);
    }

    private void newTab() {
        addNewTab(null);
        suggestions.setText("");
        screen.showHomePanel(true);
        updateToolbarState();
        updateTabCount();
        suggestions.hide();
    }

    private void goHome() {
        Tab t = tabManager.getActiveTab();
        if (t == null) return;
        t.url = "";
        t.session.loadUri("about:blank");
        suggestions.setText("");
        screen.showHomePanel(true);
    }

    private void navigateToUrl(String url) {
        Tab t = tabManager.getActiveTab();
        if (t == null) return;
        screen.showHomePanel(false);
        t.session.loadUri(url);
    }

    private void navigateFromInput(String input) {
        if (looksLikeUrl(input)) {
            String url = input.contains("://") ? input : "https://" + input;
            navigateToUrl(url);
        } else {
            navigateToUrl(BRAVE_SEARCH_URL + urlEncode(input));
        }
    }

    private static boolean looksLikeUrl(String input) {
        if (input == null || input.isEmpty()) return false;
        if (input.contains(" ")) return false;
        if (input.contains("://")) return true;
        return input.contains(".");
    }

    private static String urlEncode(String s) {
        return android.net.Uri.encode(s);
    }

    // ------------------------------------------------------------------
    // Toolbar / tab count
    // ------------------------------------------------------------------
    public void updateToolbarState() {
        Tab t = tabManager.getActiveTab();
        runOnUiThread(() -> {
            boolean fwd = t != null && t.canGoForward;
            forwardButton.setAlpha(fwd ? 1f : 0.35f);
            forwardButton.setEnabled(fwd);
            if (t != null && !urlBar.hasFocus() && !t.url.isEmpty()) {
                suggestions.setText(t.url);
            }
        });
    }

    private void updateTabCount() {
        runOnUiThread(() -> {
            int total = tabManager.count();
            int current = tabManager.getActiveIndex() + 1;
            tabCountText.setText(current + "/" + total);
        });
    }

    // ------------------------------------------------------------------
    // Exit
    // ------------------------------------------------------------------
    private void showExitConfirmation() {
        dialogs.showExitConfirmation(() -> {
            persistAllTabs();
            finishAndRemoveTask();
        });
    }

    // ------------------------------------------------------------------
    // TabManager.Listener
    // ------------------------------------------------------------------
    @Override
    public void onTabsChanged() {
        updateTabCount();
        if (tabManager.count() == 0) newTab();
    }

    @Override
    public void onActiveTabChanged(Tab tab) {
        if (tab == null) return;
        geckoView.setSession(tab.session);
        CompositorController cc = tab.session.getCompositorController();
        if (cc != null) cc.setClearColor(android.graphics.Color.BLACK);
        suggestions.hide();
        screen.showHomePanel(tab.url.isEmpty());
        updateToolbarState();
        updateTabCount();
    }

    // ------------------------------------------------------------------
    // PopupsController.Host
    // ------------------------------------------------------------------
    @Override public void onNewTab() { newTab(); }
    @Override public void onReload() {
        Tab t = tabManager.getActiveTab();
        if (t != null) t.session.reload();
    }
    @Override public void onHome() { goHome(); }
    @Override public void onShare() {
        Tab t = tabManager.getActiveTab();
        if (t == null || t.url.isEmpty()) return;
        PopupsController.shareText(this, t.url);
    }
    @Override public void onShowHistory() {
        dialogs.showHistory(this::navigateToUrl);
    }
    @Override public void onShowExit() { showExitConfirmation(); }
    @Override public void onOpenInNewTab(String url) {
        Tab t = addNewTab(null);
        t.session.loadUri(url);
    }

    // ------------------------------------------------------------------
    // SuggestionsController.Listener
    // ------------------------------------------------------------------
    @Override
    public void onSuggestionPicked(String url) {
        navigateToUrl(url);
        urlBar.clearFocus();
        screen.hideKeyboard();
        suggestions.hide();
    }

    // ------------------------------------------------------------------
    // BrowserDelegates.Host
    // ------------------------------------------------------------------
    @Override public TabManager getTabManager() { return tabManager; }
    @Override public HistoryStore getHistoryStore() { return history; }

    @Override
    public void persistAllTabs() {
        // Same synchronous call, same threads, same semantics as before.
        TabPersistence.save(this, tabManager);
    }

    @Override public void removeFlashGuard() { screen.removeFlashGuard(); }
    @Override public void setProgress(int progress) { screen.setProgress(progress); }
    @Override public void showContextMenu(int x, int y,
                                          GeckoSession.ContentDelegate.ContextElement element) {
        popups.showContextMenu(x, y, element);
    }
    @Override public void setFullScreen(boolean fullScreen) {
        screen.setFullScreenMode(fullScreen);
    }
    @Override public Tab createTabAndLoad(String uri) {
        Tab t = addNewTab(null);
        t.session.loadUri(uri);
        return t;
    }
    @Override public void requestAndroidPermissions(
            String[] permissions, GeckoSession.PermissionDelegate.Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            callback.grant();
            return;
        }
        boolean allGranted = true;
        for (String p : permissions) {
            if (checkSelfPermission(p)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            callback.grant();
        } else {
            pendingPermissionCallback = callback;
            requestPermissions(permissions, REQ_ANDROID_PERMS);
        }
    }
    @Override public void showContentPermissionDialog(
            String uri, int type, org.mozilla.geckoview.GeckoResult<Integer> result) {
        dialogs.showContentPermissionDialog(uri, type, result);
    }
    @Override public void onPageStarted(Tab tab, String url) {
        if (tab == tabManager.getActiveTab() && !urlBar.hasFocus()) {
            suggestions.setText(url);
        }
        suggestions.hide();
        screen.showHomePanel(false);
        screen.removeFlashGuard();
        screen.setProgress(0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_ANDROID_PERMS || pendingPermissionCallback == null) return;
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted) pendingPermissionCallback.grant();
        else pendingPermissionCallback.reject();
        pendingPermissionCallback = null;
    }

    // ------------------------------------------------------------------
    // Lifecycle — persistence calls preserved verbatim
    // ------------------------------------------------------------------
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); persistAllTabs(); }
    @Override public void onLowMemory() { super.onLowMemory(); persistAllTabs(); }

    @Override protected void onResume() {
        super.onResume();
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(true);
            t.session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        }
    }

    @Override protected void onPause() {
        persistAllTabs();
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(false);
            t.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
        }
        super.onPause();
    }

    @Override protected void onStop() { persistAllTabs(); super.onStop(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        List<GeckoSession.SessionState> states = new ArrayList<>();
        for (Tab t : tabManager.getTabs()) {
            if (t.latestState != null) states.add(t.latestState);
        }
        sPersistedStates = states;
        sPersistedActiveIndex = tabManager.getActiveIndex();
    }

    @Override protected void onDestroy() {
        if (popups != null) popups.dismissAll();
        if (suggestions != null) suggestions.dispose();
        if (geckoView != null) geckoView.setSession(null);
        if (tabManager != null) tabManager.closeAll();
        if (history != null) history.close();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
