package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.provider.DocumentsContract;
import android.provider.Settings;
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
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity
        implements ComponentCallbacks2, TabManager.Listener,
                   BrowserDelegates.Host, PopupsController.Host,
                   SuggestionsController.Listener, ExtensionManager.Host,
                   FindInPageController.Host {

    private static final String TAG = "SpoonGecko";
    private static final String BRAVE_SEARCH_URL = "https://search.brave.com/search?q=";
    private static final int REQ_ANDROID_PERMS = 0x5151;
    private static final int REQ_LOCAL_NETWORK = 0x5152;   // [LAN] Android 17 local network access
    // [F13] Keys for the saved-instance-state Bundle (string lists only —
    // parceling SessionState through a Bundle would hit the broken CREATOR).
    private static final String KEY_TAB_STATES = "tabs_states_v2";
    private static final String KEY_TAB_URLS = "tabs_urls_v2";
    private static final String KEY_TAB_TITLES = "tabs_titles_v2";
    private static final String KEY_ACTIVE_TAB = "tabs_active_v2";
    private static final String KEY_TAB_DESKTOP = "tabs_desktop_v2";    // [DESKTOP] boolean[] per tab
    /**
     * [TTLE] A saved-instance Bundle is shipped through a Binder transaction
     * when the activity stops ("activityStopped"), and that transaction is
     * rejected — TransactionTooLargeException, a real crash on minimizing —
     * once the parcel approaches the ~1 MB shared Binder buffer (a 609 KB
     * Bundle was observed crashing exactly there). Session data whose
     * estimated parcel size exceeds this budget stays OUT of the Bundle and
     * is saved to disk instead (TabPersistence holds the same tabs, so the
     * restore is identical); smaller sessions keep the instant in-memory
     * Bundle restore, byte for byte as before. The budget leaves a wide
     * margin: 1/3 of the observed crashing size, 1/5 of the buffer itself.
     */
    private static final long MAX_SAVED_STATE_BUNDLE_BYTES = 200 * 1024;
    private static final int REQ_POST_NOTIFICATIONS = 0x5153;           // [MEDIA] media notification (Android 13+)
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_MEDIA_NOTIF_ASKED = "media_notif_asked";

    /**
     * [PERF] When true, only the selected tab's session is opened (and its
     * page loaded) while restoring tabs after a restart; every other restored
     * tab keeps its saved state and is opened the first time it is selected.
     * Set to false to restore exactly like before (all tabs opened at launch).
     */
    private static final boolean LAZY_TAB_RESTORE = true;

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
    private FindInPageController findInPage;   // [FIND] the find-in-page bar

    private GeckoSession.PermissionDelegate.Callback pendingPermissionCallback;

    /** [LAN] Address waiting for the local-network permission prompt (null = none showing). */
    private String pendingLocalNetworkUrl;
    /** [LAN] Address to load when the user returns from Settings with access granted. */
    private String retryAfterSettingsUrl;

    /** True while this activity is being destroyed — blocks tab revival below. */
    private boolean destroying = false;

    /** [MEDIA] True between onResume and onPause. */
    private boolean resumed = false;
    /** [MEDIA] The tab that was active before the current one (its priority is lowered on a switch). */
    private Tab lastActiveTab;

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

        // [FIND] Created before restoreTabs(): switching to the restored tab
        // (which happens inside restoreTabs) must find the controller in place.
        findInPage = new FindInPageController(this, this);

        // [MEDIA] Background playback: keep the process priority of playing pages high.
        MediaPlaybackController.get().setListener(new MediaPlaybackController.Listener() {
            @Override public void onMediaStateChanged() {
                if (destroying || tabManager == null) return;
                for (Tab t : tabManager.getTabs()) updatePriority(t);
            }
            @Override public void onMediaStarted() { askForNotificationPermissionOnce(); }
        });

        setupBackHandler();
        setupInputHandlers();

        sRuntime = ensureRuntime(this);

        // --- Extension support -------------------------------------------------
        ExtensionManager.get().initialize(this);
        ExtensionManager.get().setHost(this);
        ExtensionManager.get().attachToolbar(extensionActions);
        ExtensionManager.get().maybeCheckForUpdatesOnStartup();
        DownloadEngine.get(this).clearStaleNotifications();   // [DOWNLOAD] leftovers of a run that was killed
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
        // [FIND] Close the find bar before anything else — like the suggestions
        // dropdown, it must not survive the first back press.
        if (findInPage != null && findInPage.isVisible()) {
            findInPage.close();
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
            final boolean[] desktopFlags = savedInstanceState.getBooleanArray(KEY_TAB_DESKTOP);
            if (states != null && !states.isEmpty()) {
                final int active = clampIndex(
                        savedInstanceState.getInt(KEY_ACTIVE_TAB, 0), states.size());
                for (int i = 0; i < states.size(); i++) {
                    String s = states.get(i);
                    GeckoSession.SessionState st = null;
                    if (s != null && !s.isEmpty()) {
                        try { st = GeckoSession.SessionState.fromString(s); }
                        catch (Exception ignored) {}
                    }
                    addRestoredTab(st,
                            urls != null && i < urls.size() ? urls.get(i) : "",
                            titles != null && i < titles.size() ? titles.get(i) : "",
                            !LAZY_TAB_RESTORE || i == active,
                            desktopFlags != null && i < desktopFlags.length && desktopFlags[i]);
                }
                tabManager.switchTo(active);
                screen.showHomePanel(isActiveTabEmpty());
                return;
            }
        }

        TabPersistence.Restored disk = TabPersistence.load(this);
        if (!disk.states.isEmpty()) {
            final int active = clampIndex(disk.activeIndex, disk.states.size());
            for (int i = 0; i < disk.states.size(); i++) {
                addRestoredTab(disk.states.get(i), disk.urls.get(i), disk.titles.get(i),
                        !LAZY_TAB_RESTORE || i == active,
                        i < disk.desktop.size() && disk.desktop.get(i));
            }
            tabManager.switchTo(active);
            screen.showHomePanel(isActiveTabEmpty());
            return;
        }
        addNewTab(null);
        screen.showHomePanel(true);
    }

    private static int clampIndex(int index, int count) {
        return Math.max(0, Math.min(index, count - 1));
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
     *
     * [PERF] With openNow == false the session is only prepared (state
     * restored, delegates attached) and NOT opened: no content process work
     * and no page load until the tab is first selected — onActiveTabChanged()
     * opens it then. The tab's saved state is untouched, so it is persisted
     * again unchanged in the meantime.
     */
    private Tab addRestoredTab(GeckoSession.SessionState state, String url, String title,
                               boolean openNow, boolean desktop) {
        Tab tab = tabManager.createTabWithState(state);
        tab.url = url == null ? "" : url;
        tab.title = title == null ? "" : title;
        tab.latestState = state;
        // [DESKTOP] Must be in place BEFORE the session opens: opening restores
        // (and so loads) the saved page, which has to use the saved user agent.
        if (desktop) setDesktopMode(tab, true);
        delegates.attach(tab);
        if (openNow) {
            tab.session.open(sRuntime);
            tabManager.notifyTabsChanged();
            tabManager.notifyActiveChanged();
        } else {
            tabManager.notifyTabsChanged();
        }
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
        // [LAN] IP addresses and "localhost" (router, modem, NAS ...) default to
        // http:// because such devices almost never serve HTTPS on the default
        // port; everything else is handled exactly as before. See LocalAddress.
        String url = LocalAddress.toNavigableUrl(input);
        if (url != null) {
            navigateToUrl(url);
        } else {
            navigateToUrl(BRAVE_SEARCH_URL + urlEncode(input));
        }
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
            persistAllTabsNow();
            finishAndRemoveTask();
        });
    }

    // ------------------------------------------------------------------
    // TabManager.Listener
    // ------------------------------------------------------------------
    @Override
    public void onTabsChanged() {
        if (destroying) return;   // fix for the destroy-revive loop (C4)
        MediaPlaybackController.get().prune();   // [MEDIA] drop media of tabs that were closed
        updateTabCount();
        if (tabManager.count() == 0) newTab();
    }

    @Override
    public void onActiveTabChanged(Tab tab) {
        if (tab == null) return;
        // [FIND] The find bar belongs to the page it was opened on.
        if (findInPage != null && findInPage.isVisible()) findInPage.close();
        // [POPUP] A tab handed to GeckoView is attached by showWhenOpened() once GeckoView has opened it.
        // Opening it here would make GeckoView reject it ("Must use an unopened GeckoSession instance").
        if (tab.awaitingOpen) return;
        // [PERF] A lazily restored tab has a session that was never opened;
        // open it the first time it is shown (open() must precede setSession()).
        if (!tab.session.isOpen()) tab.session.open(sRuntime);
        geckoView.setSession(tab.session);
        CompositorController cc = tab.session.getCompositorController();
        if (cc != null) cc.setClearColor(android.graphics.Color.BLACK);
        suggestions.hide();
        screen.showHomePanel(tab.url.isEmpty());
        updateToolbarState();
        updateTabCount();
        ExtensionManager.get().setActiveSession(tab.session);   // tabs.onActivated
        // [MEDIA] Process priority follows the visible tab: raise this one and lower
        // the previous one, unless that one is playing media.
        final Tab previous = lastActiveTab;
        lastActiveTab = tab;
        if (previous != null && previous != tab) updatePriority(previous);
        updatePriority(tab);
    }

    /**
     * [MEDIA] A tab's process gets high priority while it is the visible one
     * (app in the foreground) or while it is playing media — even in the
     * background, which is what keeps playback alive when the app is not on
     * screen. Everything else stays at the default.
     */
    private void updatePriority(Tab t) {
        if (t == null || !t.session.isOpen()) return;
        final boolean high = (resumed && t == tabManager.getActiveTab())
                || MediaPlaybackController.get().wantsHighPriority(t.session);
        if (high == t.priorityHigh) return;
        t.priorityHigh = high;
        t.session.setPriorityHint(high ? GeckoSession.PRIORITY_HIGH : GeckoSession.PRIORITY_DEFAULT);
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
    // [FIND] PopupsController.Host + FindInPageController.Host
    @Override public void onFindInPage() {
        if (findInPage != null) findInPage.show();
    }
    @Override public Tab activeSearchTab() {
        return tabManager == null ? null : tabManager.getActiveTab();
    }
    @Override public void onOpenInNewTab(String url) {
        final Tab opener = tabManager.getActiveTab();
        Tab t = addNewTab(null);
        if (opener != null && opener.desktopMode) setDesktopMode(t, true);   // [DESKTOP] keep the site type
        t.session.loadUri(url);
    }

    // ------------------------------------------------------------------
    // [DESKTOP] "Desktop site" toggle
    // ------------------------------------------------------------------

    /** Sets a tab's mode. Takes effect on that tab's next load. */
    private void setDesktopMode(Tab t, boolean on) {
        t.desktopMode = on;
        DesktopMode.apply(t.session, on);
    }

    @Override public boolean isDesktopMode() {
        final Tab t = tabManager.getActiveTab();
        return t != null && t.desktopMode;
    }

    @Override public void onToggleDesktopMode() {
        final Tab t = tabManager.getActiveTab();
        if (t == null) return;
        final boolean on = !t.desktopMode;
        setDesktopMode(t, on);
        persistAllTabs();   // coalesced save, so the choice survives a restart
        android.widget.Toast.makeText(this, on ? "Desktop site" : "Mobile site",
                android.widget.Toast.LENGTH_SHORT).show();
        if (t.url == null || t.url.isEmpty()) return;     // nothing loaded: applies to the next page
        // Switching back to mobile: YouTube's "?app=desktop" would keep the desktop layout.
        final String cleaned = on ? null : DesktopMode.mobileReloadTarget(t.url);
        DesktopMode.reloadWithNewAgent(t.session, cleaned != null ? cleaned : t.url);
    }

    // ------------------------------------------------------------------
    // ExtensionManager.Host
    // ------------------------------------------------------------------
    @Override
    public GeckoSession openExtensionTab(String url) {
        // Two callers look identical from here:
        //  - an extension's tabs.create: GeckoView takes the returned session, opens it itself and
        //    Gecko loads the URL ("there is no need to load the URL manually" per the API docs);
        //  - the app showing an extension page (its options): nobody else will open the session.
        // The session is therefore returned unopened; if GeckoView has not adopted it after a short
        // grace period, we open it and load the URL ourselves.
        final Tab t = createUnopenedTab();
        t.pendingUrl = url;
        showWhenOpened(t, true);
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

    /**
     * [PERF] Called by the page delegates (onSessionStateChange / onPageStop),
     * which fire in bursts while a page loads or scrolls. The save is
     * coalesced (see TabPersistence.saveSoon) instead of re-serializing every
     * tab on each event. Lifecycle moments use persistAllTabsNow() below.
     */
    @Override
    public void persistAllTabs() {
        if (destroying) return;
        TabPersistence.saveSoon(this, tabManager);
    }

    /**
     * Immediate save for lifecycle moments (pause, stop, trim, exit): same
     * synchronous call, same threads, same semantics as before. Unchanged
     * tabs are skipped inside TabPersistence, so repeated calls are cheap.
     */
    private void persistAllTabsNow() {
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
        // [FIND] The find bar must not float over a fullscreen video.
        if (fullScreen && findInPage != null && findInPage.isVisible()) findInPage.close();
        screen.setFullScreenMode(fullScreen);
    }
    // [F6] Contract of NavigationDelegate.onNewSession(): return a NEW,
    // already-open session and DO NOT load the URI — GeckoView loads finalUri
    // into the returned session itself. Pre-loading it here (the old
    // createTabAndLoad) caused a duplicate load for every target=_blank link.
    // ------------------------------------------------------------------
    // [POPUP] Tabs that GeckoView opens itself
    // ------------------------------------------------------------------
    //
    // When a page asks for a new window (target=_blank, window.open) or an extension calls
    // tabs.create, GeckoView needs a NEW, UNOPENED GeckoSession. It opens that session itself,
    // wiring it to the window the page requested (that is what keeps window.opener working).
    // Handing it a session that is already open fails with
    // "Must use an unopened GeckoSession instance", which crashed the app for every such link.
    // So these tabs are created unopened, handed back, and only shown once they are open.

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Creates a tab whose session is NOT opened (and not attached to the view) yet. */
    private Tab createUnopenedTab() {
        final Tab opener = tabManager.getActiveTab();
        final Tab t = tabManager.createTab();
        t.awaitingOpen = true;
        t.opener = opener;
        if (opener != null && opener.desktopMode) setDesktopMode(t, true);   // [DESKTOP] keep the site type
        delegates.attach(t);
        return t;
    }

    @Override public Tab createNewSessionTab() {
        final Tab t = createUnopenedTab();
        t.openedByPage = true;
        return t;
    }

    @Override public void onNewSessionReturned(Tab t) {
        showWhenOpened(t, false);
    }

    /**
     * Shows a tab whose session GeckoView is about to open (see SessionAdoption for the timing).
     *
     * @param mayOpenItself true when nobody but us can open it (the app asked for the tab, as with
     *                      an extension's options page): after a short grace period we open it and
     *                      load {@code pendingUrl}. false when GeckoView must adopt it: if it never
     *                      does, the empty tab is dropped.
     */
    private void showWhenOpened(final Tab t, final boolean mayOpenItself) {
        SessionAdoption.await(new SessionAdoption.Scheduler() {
            @Override public void post(Runnable r) { uiHandler.post(r); }
            @Override public void postDelayed(Runnable r, long delayMs) { uiHandler.postDelayed(r, delayMs); }
        }, new SessionAdoption.Target() {
            @Override public boolean isGone() {
                return destroying || !tabManager.getTabs().contains(t);
            }
            @Override public boolean isOpen() {
                return t.session.isOpen();
            }
            @Override public void adopted() {
                t.awaitingOpen = false;            // GeckoView opened it (and loads the address itself)
                tabManager.notifyTabsChanged();
                tabManager.notifyActiveChanged();
            }
            @Override public void openItself() {
                t.awaitingOpen = false;
                t.session.open(sRuntime);
                if (t.pendingUrl != null && !t.pendingUrl.isEmpty()) t.session.loadUri(t.pendingUrl);
                tabManager.notifyTabsChanged();
                tabManager.notifyActiveChanged();
            }
            @Override public void dropped() {
                t.awaitingOpen = false;            // GeckoView never took the window
                final int idx = tabManager.getTabs().indexOf(t);
                if (idx >= 0) tabManager.closeTab(idx);
            }
        }, mayOpenItself);
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

    // ------------------------------------------------------------------
    // [MEDIA] Notification permission (Android 13+)
    // ------------------------------------------------------------------

    /**
     * The playback notification (and its controls) is only shown if the user
     * allowed notifications. Ask once, the first time something plays while
     * the app is on screen; playback itself works either way.
     */
    private void askForNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || destroying || isFinishing() || !resumed) return;
        final String perm = "android.permission.POST_NOTIFICATIONS";
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        final android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_MEDIA_NOTIF_ASKED, false)) return;
        prefs.edit().putBoolean(PREF_MEDIA_NOTIF_ASKED, true).apply();
        requestPermissions(new String[]{perm}, REQ_POST_NOTIFICATIONS);
    }

    // ------------------------------------------------------------------
    // [LAN] Android 17 "local network" permission
    // ------------------------------------------------------------------

    /**
     * Android 17 blocks connections to the user's own network (router, modem,
     * NAS ...) for apps without the ACCESS_LOCAL_NETWORK runtime permission;
     * the symptom is a long hang followed by a timeout. Explain, ask, then
     * load the page the user wanted. A no-op on older Android versions and
     * once access has been granted (which also keeps a failing load from
     * ever re-triggering itself).
     */
    @Override
    public void requestLocalNetworkAccess(String uri) {
        if (uri == null || destroying || isFinishing()) return;
        if (!LocalNetworkAccess.isMissing(this)) return;
        if (pendingLocalNetworkUrl != null) {   // a prompt is already showing: keep the newest target
            pendingLocalNetworkUrl = uri;
            return;
        }
        pendingLocalNetworkUrl = uri;
        new AlertDialog.Builder(this)
                .setTitle("Allow local network access?")
                .setMessage("This address is on your local network, such as your router. "
                        + "Android needs your permission before SpoonGecko can connect to "
                        + "devices on it.")
                .setPositiveButton("Continue", (d, w) -> requestPermissions(
                        new String[]{LocalNetworkAccess.PERMISSION}, REQ_LOCAL_NETWORK))
                .setNegativeButton("Not now", (d, w) -> cancelLocalNetworkRequest())
                .setOnCancelListener(d -> cancelLocalNetworkRequest())
                .show();
    }

    private void cancelLocalNetworkRequest() {
        pendingLocalNetworkUrl = null;
        // navigateToUrl() hides the home panel before the load is held back; put it back.
        screen.showHomePanel(isActiveTabEmpty());
    }

    private void showLocalNetworkDenied(final String url) {
        if (destroying || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Local network access is off")
                .setMessage("Without it Android blocks SpoonGecko from reaching your router and "
                        + "other devices on your network. You can turn it on under Permissions "
                        + "\u203a Nearby devices.")
                .setPositiveButton("Open settings", (d, w) -> {
                    retryAfterSettingsUrl = url;
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null)));
                    } catch (Exception e) {
                        Log.w(TAG, "open app settings failed", e);
                        retryAfterSettingsUrl = null;
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    // ------------------------------------------------------------------
    // [DOWNLOAD]
    // ------------------------------------------------------------------

    /** A page started a download (attachment, download attribute, a type GeckoView can't show, blob:). */
    @Override
    public boolean onDownloadResponse(final Tab tab, WebResponse response) {
        try {
            askForNotificationPermissionOnce();
            Runnable whenStreamDone = null;
            if (tab.openedByPage && !tab.hasContent) {
                // A new window opened only to deliver a file stays empty: return to the page that
                // opened it now, and close the empty window once the transfer no longer needs it.
                final Tab opener = tab.opener;
                if (opener != null && tab == tabManager.getActiveTab()) {
                    final int at = tabManager.getTabs().indexOf(opener);
                    if (at >= 0) tabManager.switchTo(at);
                }
                whenStreamDone = () -> {
                    final int idx = tabManager.getTabs().indexOf(tab);
                    if (idx >= 0 && !tab.hasContent) tabManager.closeTab(idx);
                };
            }
            DownloadEngine.get(this).enqueue(sRuntime,
                    DownloadEngine.requestFromResponse(response, whenStreamDone));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "download engine could not start, using the fallback", e);
            return false;
        }
    }

    /** The user followed a link to a file, or chose Download in the long-press menu. */
    @Override
    public void onDownloadLink(String url, String referrer) {
        try {
            askForNotificationPermissionOnce();
            DownloadEngine.get(this).enqueue(sRuntime, DownloadRequest.forUrl(url, referrer));
        } catch (RuntimeException e) {
            Log.w(TAG, "download engine could not start, using the fallback", e);
            downloadFile(url, null, null);
        }
    }

    @Override public void onDownloadUrl(String url) {
        final Tab t = tabManager.getActiveTab();
        onDownloadLink(url, t == null || t.url == null || t.url.isEmpty() ? null : t.url);
    }

    @Override public void onShowDownloads() {
        // [DOWNLOAD] Open the folder the files land in with the device's file
        // manager: the system Files app (and most third-party managers) handle
        // ACTION_VIEW on a directory document. The system downloads list is only
        // the fallback now — on several devices it is missing, and on others it
        // hides the files this app saves through MediaStore.
        if (openInFileManager(downloadsDocumentId())) return;
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            Log.w(TAG, "no app can show the downloads", e);
            android.widget.Toast.makeText(this, "No app can show the downloads list",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** [DOWNLOAD] Shows a folder through any installed file manager; false if none took it. */
    private boolean openInFileManager(String documentId) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", documentId),
                    DocumentsContract.Document.MIME_TYPE_DIR));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "no file manager for the Downloads folder", e);
            return false;
        }
    }

    /**
     * [DOWNLOAD] The folder DownloadEngine writes to, as a document ID of the
     * external-storage provider ("primary" is the phone's main storage):
     * Android 10+ saves into the public Downloads folder through MediaStore;
     * older versions use the app's own external Downloads folder.
     */
    private String downloadsDocumentId() {
        if (Build.VERSION.SDK_INT >= 29) {
            return "primary:" + Environment.DIRECTORY_DOWNLOADS;
        }
        final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) return "primary:" + Environment.DIRECTORY_DOWNLOADS;
        return "primary:Android/data/" + getPackageName()
                + "/files/" + Environment.DIRECTORY_DOWNLOADS;
    }

    /** Fallback only: Android's DownloadManager can't use cookies, POST bodies or blob: URLs. */
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
        if (requestCode == REQ_POST_NOTIFICATIONS) return;   // [MEDIA] nothing to do; the next update shows it
        if (requestCode == REQ_LOCAL_NETWORK) {
            final String url = pendingLocalNetworkUrl;
            pendingLocalNetworkUrl = null;
            if (grantResults.length == 0) {                  // request interrupted: treat as cancelled
                screen.showHomePanel(isActiveTabEmpty());
                return;
            }
            if (grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Tab t = tabManager.getActiveTab();
                if (t != null && url != null) {
                    screen.showHomePanel(false);
                    t.session.loadUri(url);
                }
            } else {
                screen.showHomePanel(isActiveTabEmpty());
                showLocalNetworkDenied(url);
            }
            return;
        }
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
    // Lifecycle — immediate saves at every moment the process could be lost
    // ------------------------------------------------------------------
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); persistAllTabsNow(); }
    @Override public void onLowMemory() { super.onLowMemory(); persistAllTabsNow(); }

    @Override protected void onResume() {
        super.onResume();
        ExtensionManager.get().setCurrentActivity(this);   // for extension prompts/popups
        resumed = true;
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(true);
            updatePriority(t);   // HIGH: it is the visible tab (see updatePriority)
        }
        // [LAN] Back from Settings after being sent there to allow local network access.
        if (retryAfterSettingsUrl != null) {
            final String url = retryAfterSettingsUrl;
            retryAfterSettingsUrl = null;
            Tab active = tabManager == null ? null : tabManager.getActiveTab();
            if (active != null && LocalNetworkAccess.isGranted(this)) {
                screen.showHomePanel(false);
                active.session.loadUri(url);
            }
        }
    }

    @Override protected void onPause() {
        persistAllTabsNow();
        ExtensionManager.get().setCurrentActivity(null);
        resumed = false;
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(false);
            // [MEDIA] Back to the default priority — unless this tab is playing media,
            // in which case it must stay high or Android reclaims it within a minute or two.
            updatePriority(t);
        }
        MediaPlaybackController.get().onAppBackgrounded();   // [MEDIA] make sure the service is up
        super.onPause();
    }

    @Override protected void onStop() { persistAllTabsNow(); super.onStop(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // [F13] Real data in the Bundle: string lists only (see TabPersistence
        // for why SessionState must not be parceled through a Bundle). This is
        // what brings the session back after the process is killed in the
        // background; the old code stored everything in static fields, which
        // die with the process, and relied on a broken disk restore.
        //
        // [TTLE] But the Bundle is NOT free storage: Android hands it to the
        // system process through a Binder transaction when the activity stops
        // ("activityStopped"), and one that is too large throws
        // TransactionTooLargeException from PendingTransactionActions — the
        // crash report showed tabs_states_v2 alone at 606908 bytes. So the
        // states are put in ONLY while they fit the budget below; an
        // oversized session set skips them (and the Bundle restore) and is
        // saved to disk right here, which restores the same tabs through
        // TabPersistence (states, urls, titles, desktop flags, active index —
        // the identical data, written at every pause/stop/trim and on page
        // events besides this one).
        ArrayList<String> states = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        long parcelBytes = 48;   // the four Bundle entries' own headers and array counts
        final List<Tab> allTabs = tabManager.getTabs();
        final boolean[] desktopFlags = new boolean[allTabs.size()];
        for (int i = 0; i < allTabs.size(); i++) desktopFlags[i] = allTabs.get(i).desktopMode;
        boolean overBudget = false;
        for (Tab t : allTabs) {
            String s = null;
            if (t.latestState != null) {
                try { s = t.latestState.toString(); } catch (Exception ignored) {}
            }
            // Estimated parcel cost of one entry of each list: a String in a
            // Parcel costs at worst 2 bytes per char (UTF-16) plus its length
            // header, and each ArrayList entry adds its own small header.
            parcelBytes += entryBytes(s) + entryBytes(t.url) + entryBytes(t.title);
            states.add(s == null ? "" : s);
            urls.add(t.url == null ? "" : t.url);
            titles.add(t.title == null ? "" : t.title);
            if (parcelBytes > MAX_SAVED_STATE_BUNDLE_BYTES) { overBudget = true; break; }
        }
        if (overBudget) {
            // Same tabs, same format, same restore path — just written to disk
            // now instead of riding a Binder transaction that would crash.
            // (viewHierarchyState stays in the Bundle; only our big lists go.)
            persistAllTabsNow();
            return;
        }
        out.putStringArrayList(KEY_TAB_STATES, states);
        out.putStringArrayList(KEY_TAB_URLS, urls);
        out.putStringArrayList(KEY_TAB_TITLES, titles);
        out.putInt(KEY_ACTIVE_TAB, tabManager.getActiveIndex());
        out.putBooleanArray(KEY_TAB_DESKTOP, desktopFlags);
    }

    /** [TTLE] Worst-case bytes one of our Bundle strings costs inside the parcel. */
    private static long entryBytes(String s) {
        if (s == null) return 8;
        return 16 + 2 * (long) s.length();
    }

    @Override protected void onDestroy() {
        destroying = true;
        // [PERF] A queued coalesced save must not fire after the tabs below are
        // closed (it would otherwise be able to overwrite the saved tabs).
        TabPersistence.cancelPendingSave();
        if (popups != null) popups.dismissAll();
        if (suggestions != null) suggestions.dispose();
        // [MEDIA] The tabs are about to be closed: stop the playback service and focus with them.
        MediaPlaybackController.get().shutdown();
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
