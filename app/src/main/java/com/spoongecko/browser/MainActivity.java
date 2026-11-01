package com.spoongecko.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.mozilla.geckoview.CompositorController;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity
        implements ComponentCallbacks2, TabManager.Listener,
                   BrowserDelegates.Host, PopupsController.Host,
                   SuggestionsController.Listener, ExtensionManager.Host {
                
    private static final String TAG = "SpoonGecko";
    private static final String BRAVE_SEARCH_URL = "https://search.brave.com/search?q=";
    private static final int REQ_ANDROID_PERMS = 0x5151;
    // [F13] Keys for the saved-instance-state Bundle (string lists only —
    // parceling SessionState through a Bundle would hit the broken CREATOR).
    private static final String KEY_TAB_STATES = "tabs_states_v2";
    private static final String KEY_TAB_URLS = "tabs_urls_v2";
    private static final String KEY_TAB_TITLES = "tabs_titles_v2";
    private static final String KEY_ACTIVE_TAB = "tabs_active_v2";
                
    private static GeckoRuntime sRuntime;

    private TabManager tabManager;
    private GeckoView geckoView;
    private HistoryStore history;

    private EditText urlBar;
    private EditText searchBar;
    private FrameLayout tabButton;
    private TextView tabCountText;
    private ImageButton forwardButton;
    private LinearLayout extensionActions;

    private ScreenController screen;
    private SuggestionsController suggestions;
    private PopupsController popups;
    private BrowserDialogs dialogs;
    private BrowserPrompts prompts;
    private BrowserDelegates delegates;

    private GeckoSession.PermissionDelegate.Callback pendingPermissionCallback;

    /** True while this activity is being destroyed — blocks tab revival below. */
    private boolean destroying = false;

    private static synchronized GeckoRuntime ensureRuntime(Activity activity) {
        if (sRuntime == null) {
            // Runtime creation (and its settings) lives in ExtensionManager so
            // background update checks can create the same runtime without a UI.
            sRuntime = ExtensionManager.get().ensureRuntime(activity.getApplicationContext());
        }
        return sRuntime;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [C5] If the previous run crashed, show its stack trace first so it
        // can be copied/shared from the phone (no adb available in this setup).
        App.showLastCrashIfAny(this);

        // Fix for the startup crash (C2): content must be attached BEFORE any
        // controller looks up views; ScreenController is created exactly once.
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);
        urlBar = findViewById(R.id.url_bar);
        searchBar = findViewById(R.id.search_bar);
        tabButton = findViewById(R.id.tab_button);
        tabCountText = findViewById(R.id.tab_count_text);
        forwardButton = findViewById(R.id.forward_button);
        ImageButton menuButton = findViewById(R.id.menu_button);
        ListView suggestionsList = findViewById(R.id.suggestions_list);
        extensionActions = findViewById(R.id.extension_actions);

        geckoView.setBackgroundColor(android.graphics.Color.BLACK);
        history = new HistoryStore(this);

        screen = new ScreenController(this);
        screen.makeImmersive();
        screen.installFlashGuard();
        screen.setupWindowInsets();
        new Handler(Looper.getMainLooper()).postDelayed(screen::removeFlashGuard, 1500);

        suggestions = new SuggestionsController(this, urlBar, suggestionsList, history);
        suggestions.setListener(this);

        tabManager = new TabManager();
        tabManager.setListener(this);

        popups = new PopupsController(this, geckoView, menuButton, this);
        dialogs = new BrowserDialogs(this, tabManager, history);
        prompts = new BrowserPrompts(this);
        delegates = new BrowserDelegates(this, this, prompts);

        setupBackHandler();
        setupInputHandlers();

        sRuntime = ensureRuntime(this);

        // --- Extension support -------------------------------------------------
        ExtensionManager.get().initialize(this);
        ExtensionManager.get().setHost(this);
        ExtensionManager.get().attachToolbar(extensionActions);
        ExtensionManager.get().maybeCheckForUpdatesOnStartup();
        // -------------------------------------------------------------------------

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
        // [M1-fix] Close the suggestion dropdown first — it used to stay open
        // while the page navigated back underneath it.
        if (suggestions != null && suggestions.isVisible()) {
            suggestions.hide();
            return;
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
        // [F13] Restore order:
        //   1. savedInstanceState Bundle — survives process death AND activity
        //      recreation (Android re-delivers it even after the process is
        //      killed in the background). The old code ignored the Bundle
        //      completely and used static fields, which die with the process.
        //   2. Disk (TabPersistence) — survives the task being swiped away
        //      and cold starts. Previously broken (Parcel round-trip), fixed.
        //   3. Fresh tab with the home panel.
        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_TAB_STATES)) {
            ArrayList<String> states = savedInstanceState.getStringArrayList(KEY_TAB_STATES);
            ArrayList<String> urls = savedInstanceState.getStringArrayList(KEY_TAB_URLS);
            ArrayList<String> titles = savedInstanceState.getStringArrayList(KEY_TAB_TITLES);
            if (states != null && !states.isEmpty()) {
                for (int i = 0; i < states.size(); i++) {
                    String s = states.get(i);
                    GeckoSession.SessionState st = null;
                    if (s != null && !s.isEmpty()) {
                        try { st = GeckoSession.SessionState.fromString(s); }
                        catch (Exception ignored) {}
                    }
                    addRestoredTab(st,
                            urls != null && i < urls.size() ? urls.get(i) : "",
                            titles != null && i < titles.size() ? titles.get(i) : "");
                }
                tabManager.switchTo(Math.min(savedInstanceState.getInt(KEY_ACTIVE_TAB, 0),
                        tabManager.count() - 1));
                screen.showHomePanel(isActiveTabEmpty());
                return;
            }
        }

        TabPersistence.Restored disk = TabPersistence.load(this);
        if (!disk.states.isEmpty()) {
            for (int i = 0; i < disk.states.size(); i++) {
                addRestoredTab(disk.states.get(i), disk.urls.get(i), disk.titles.get(i));
            }
            tabManager.switchTo(Math.min(disk.activeIndex, tabManager.count() - 1));
            screen.showHomePanel(isActiveTabEmpty());
            return;
        }
        addNewTab(null);
        screen.showHomePanel(true);
    }

    private boolean isActiveTabEmpty() {
        Tab t = tabManager.getActiveTab();
        return t == null || t.url == null || t.url.isEmpty();
    }

    /**
     * [F13] Creates a tab from a saved state and immediately restores its
     * url / title / latestState, so the URL bar, the home-panel visibility
     * and the NEXT disk save are all correct from the very first frame.
     * latestState in particular keeps the save chain unbroken: without it,
     * a quick re-minimize before any page event would lose the tab again.
     */
    private Tab addRestoredTab(GeckoSession.SessionState state, String url, String title) {
        Tab tab = tabManager.createTabWithState(state);
        tab.url = url == null ? "" : url;
        tab.title = title == null ? "" : title;
        tab.latestState = state;
        delegates.attach(tab);
        tab.session.open(sRuntime);
        tabManager.notifyTabsChanged();
        tabManager.notifyActiveChanged();
        return tab;
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
        return Uri.encode(s);
    }

    // ------------------------------------------------------------------
    // Toolbar / tab count
    // ------------------------------------------------------------------
    @Override
    public void updateToolbarState() {
        Tab t = tabManager.getActiveTab();
        runOnUiThread(() -> {
            boolean fwd = t != null && t.canGoForward;
            forwardButton.setAlpha(fwd ? 1f : 0.35f);
            forwardButton.setEnabled(fwd);        
            // [F14] Always mirror tab.url into the bar — also when empty —
            // so a stale "about:blank" can never survive there.
            if (t != null && !urlBar.hasFocus()) {
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
        if (destroying) return;   // fix for the destroy-revive loop (C4)
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
        ExtensionManager.get().setActiveSession(tab.session);   // tabs.onActivated
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
    @Override public void onShowExtensions() {
        startActivity(new Intent(this, ExtensionsActivity.class));
    }
    @Override public void onShowExit() { showExitConfirmation(); }
    @Override public void onOpenInNewTab(String url) {
        Tab t = addNewTab(null);
        t.session.loadUri(url);
    }

    // ------------------------------------------------------------------
    // ExtensionManager.Host
    // ------------------------------------------------------------------
    @Override
    public GeckoSession openExtensionTab(String url) {
        Tab t = addNewTab(null);
        if (url != null && !url.isEmpty()) t.session.loadUri(url);
        return t.session;
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
    @Override public void setProgressBarVisible(boolean visible) {
        screen.setProgressBarVisible(visible);
    }

    @Override public void setLoadProgress(int progress) {
        screen.setProgress(progress);
    }

    @Override public void showContextMenu(int x, int y,
                                          GeckoSession.ContentDelegate.ContextElement element) {
        popups.showContextMenu(x, y, element);
    }
    @Override public void setFullScreen(boolean fullScreen) {
        screen.setFullScreenMode(fullScreen);
    }
    // [F6] Contract of NavigationDelegate.onNewSession(): return a NEW,
    // already-open session and DO NOT load the URI — GeckoView loads finalUri
    // into the returned session itself. Pre-loading it here (the old
    // createTabAndLoad) caused a duplicate load for every target=_blank link.
    @Override public Tab createNewSessionTab() {
        return addNewTab(null);
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

    @Override public void showMediaPermissionDialog(String uri,
            GeckoSession.PermissionDelegate.MediaSource[] video,
            GeckoSession.PermissionDelegate.MediaSource[] audio,
            GeckoSession.PermissionDelegate.MediaCallback callback) {
        dialogs.showMediaPermissionDialog(uri, video, audio, callback);
    }
                
    @Override public void onTabLocationChanged(Tab tab, String url) {
        // [F14] url arrives sanitized ("" for about:blank), so an empty value
        // CLEARS the bar instead of leaving "about:blank" sitting in it.
        if (tab == tabManager.getActiveTab() && !urlBar.hasFocus()) {
            suggestions.setText(url == null ? "" : url);
        }
    }

    @Override public void openExternalUri(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception e) {
            Log.e(TAG, "open external uri failed", e);
        }
    }

    @Override public void downloadFile(String url, String mime, String contentDisposition) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mime != null) req.setMimeType(mime);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String fileName = URLUtil.guessFileName(url, contentDisposition, mime);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            dm.enqueue(req);
        } catch (Exception e) {
            Log.e(TAG, "download failed", e);
        }
    }

    @Override public void onSessionGone(Tab tab) {
        // [FIX] The old code unconditionally created a new tab here, but
        // onTabsChanged() already auto-creates a replacement when the LAST tab
        // closes — so a content-process crash used to leave one extra empty
        // tab (or even two) behind.
        final String lastUrl = tab == null ? null : tab.url;
        int idx = tabManager.getTabs().indexOf(tab);
        if (idx >= 0) tabManager.closeTab(idx);
        if (tabManager.count() == 0) {
            // onTabsChanged() has just created the replacement tab; reload the
            // page that was on screen when the content process died.
            Tab t = tabManager.getActiveTab();
            if (t != null && lastUrl != null && !lastUrl.isEmpty()) {
                t.session.loadUri(lastUrl);
            }
        }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (prompts != null && prompts.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle — persistence calls preserved verbatim
    // ------------------------------------------------------------------
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); persistAllTabs(); }
    @Override public void onLowMemory() { super.onLowMemory(); persistAllTabs(); }

    @Override protected void onResume() {
        super.onResume();
        ExtensionManager.get().setCurrentActivity(this);   // for extension prompts/popups
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(true);
            t.session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        }
    }

    @Override protected void onPause() {
        persistAllTabs();
        ExtensionManager.get().setCurrentActivity(null);
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
        destroying = true;
        if (popups != null) popups.dismissAll();
        if (suggestions != null) suggestions.dispose();
        // Fix for the exit crash (C3): GeckoView.setSession() is @NonNull and
        // throws on null — the view is being torn down, nothing to detach.
        if (tabManager != null) {
            tabManager.setListener(null);   // fix for the destroy-revive loop (C4)
            tabManager.closeAll();
        }
        if (history != null) history.close();
        ExtensionManager.get().onActivityDestroyed(this);
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
