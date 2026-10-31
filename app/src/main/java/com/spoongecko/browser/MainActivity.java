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
    private static final int REQ_POST_NOTIFICATIONS = 0x5152;   // [F12]

    private static GeckoRuntime sRuntime;
    private static List<GeckoSession.SessionState> sPersistedStates;
    private static int sPersistedActiveIndex = 0;

    private TabManager tabManager;
    private GeckoView geckoView;
    private HistoryStore history;
    private TabPersistence tabPersistence;   // [P1] one reusable instance

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
    private android.window.OnBackInvokedCallback backCallback;   // [F2]

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
        tabPersistence = new TabPersistence(this);   // [P1]

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
                backCallback = this::handleBackAction;                        // [F2]
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        backCallback);
            } catch (Exception e) { Log.w(TAG, "register back callback failed", e); }
        }
    }

    /** [F2] The callback used to stay registered (and keep this activity
     *  referenced) after destroy. */
    private void unregisterBackCallback() {
        if (backCallback == null) return;
        try {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        } catch (Exception ignored) {
        }
        backCallback = null;
    }

    private void handleBackAction() {
        // [F3] This used to be a dead comment — the dropdown was never closed.
        if (suggestions != null && suggestions.isVisible()) {
            suggestions.hide();
            urlBar.clearFocus();
            screen.hideKeyboard();
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
            persistNow();               // [P1] immediate (non-debounced) save
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
        if (tab == null) {
            // [F8] All tabs are gone (e.g. closeAll during teardown): drop the
            // stale "active session" reference in the extension controller,
            // otherwise it keeps a closed GeckoSession alive.
            ExtensionManager.get().setActiveSession(null);
            return;
        }
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
        // WebExtension tabs.create: the embedder IS responsible for loading.
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
        // [P1] Hot path (every onSessionStateChange / onPageStop). The old
        // implementation built a brand-new TabPersistence — and a fresh executor
        // thread — on every call. Debounced on one reusable instance instead.
        if (tabPersistence != null) tabPersistence.scheduleSave(tabManager);
    }

    /** Immediate (non-debounced) save — used at lifecycle points and on exit. */
    private void persistNow() {
        if (tabPersistence != null && tabManager != null) {
            tabPersistence.persistNow(tabManager);
        }
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

    @Override public Tab createNewSessionTab() {
        // [F6] GeckoView loads the URI into the returned session itself
        // (onNewSession contract: "The URI is provided only for informational
        // purposes. Do not call load here."). The old createTabAndLoad()
        // pre-loaded it and caused a duplicate load on every target=_blank.
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
        // [F12] On Android 13+ the site permission alone is not enough for
        // notifications; ask for the POST_NOTIFICATIONS runtime grant right
        // after the user taps "Allow".
        Runnable onAllowed = null;
        if (type == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onAllowed = () -> requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_POST_NOTIFICATIONS);
        }
        dialogs.showContentPermissionDialog(uri, type, result, onAllowed);
    }

    @Override public void showMediaPermissionDialog(String uri,
            GeckoSession.PermissionDelegate.MediaSource[] video,
            GeckoSession.PermissionDelegate.MediaSource[] audio,
            GeckoSession.PermissionDelegate.MediaCallback callback) {
        dialogs.showMediaPermissionDialog(uri, video, audio, callback);
    }

    @Override public void onTabLocationChanged(Tab tab, String url) {
        if (tab == tabManager.getActiveTab() && !urlBar.hasFocus()) {
            suggestions.setText(url);
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
        // [F7] The old version always added an extra tab on top of the one the
        // TabManager.Listener already revives when the last tab closes, and had
        // no guard against running during teardown.
        if (destroying || tabManager == null) return;
        final String lastUrl = tab.url;
        int idx = tabManager.getTabs().indexOf(tab);
        if (idx >= 0) tabManager.closeTab(idx);   // may revive a fresh tab via onTabsChanged
        Tab replacement = tabManager.getActiveTab();
        if (replacement == null) return;
        // Only reload the crashed page into a *fresh* tab; never hijack another
        // existing tab that just became active.
        if (lastUrl != null && !lastUrl.isEmpty() && replacement.url.isEmpty()) {
            replacement.session.loadUri(lastUrl);
        }
        updateToolbarState();
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
        if (requestCode == REQ_POST_NOTIFICATIONS) return;   // [F12] best effort
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
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); persistNow(); }
    @Override public void onLowMemory() { super.onLowMemory(); persistNow(); }

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
        persistNow();
        ExtensionManager.get().setCurrentActivity(null);
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(false);
            t.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
        }
        super.onPause();
    }

    @Override protected void onStop() { persistNow(); super.onStop(); }

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
        // Final tab snapshot BEFORE the sessions are closed.
        persistNow();
        if (popups != null) popups.dismissAll();
        if (suggestions != null) suggestions.dispose();
        // [F1] The [M8] dismissal machinery existed but was never called from
        // here — a dialog open during destroy crashed with WindowLeaked.
        if (dialogs != null) dialogs.dismissAll();
        if (prompts != null) prompts.dismissAll();
        if (pendingPermissionCallback != null) {
            try { pendingPermissionCallback.reject(); } catch (Exception ignored) {}
            pendingPermissionCallback = null;
        }
        unregisterBackCallback();                 // [F2]
        if (tabManager != null) {
            tabManager.setListener(null);         // fix for the destroy-revive loop (C4)
            tabManager.closeAll();
        }
        if (history != null) history.close();
        ExtensionManager.get().onActivityDestroyed(this);
        if (tabPersistence != null) tabPersistence.shutdown();   // [P1] drain queued writes
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
