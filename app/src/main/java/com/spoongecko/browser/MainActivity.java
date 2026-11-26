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
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.mozilla.geckoview.CompositorController;
import org.mozilla.geckoview.GeckoPreferenceController;
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
    /**
     * [SELECT] Background of selected text. On Android Gecko draws the selection as the app's
     * colorAccent at only ~31% opacity, which is invisible on a black page when the accent is
     * dark. Gecko reads this "ui.highlight" override first, so the highlight is a clear blue on
     * every page (the text keeps its own colour). Alpha: see applySelectionColor().
     */
    private static final String SELECTION_HIGHLIGHT_COLOR = "rgba(10, 132, 255, 0.55)";
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

    /**
     * [KILL] A tab whose page process dies is brought back (see onSessionGone). A page that keeps
     * killing its process must not be reloaded for ever: when the same tab dies more than
     * MAX_QUICK_DEATHS times within DEATH_WINDOW_MS the tab is closed, as it always used to be.
     */
    private static final int MAX_QUICK_DEATHS = 3;
    private static final long DEATH_WINDOW_MS = 30_000L;

    private static GeckoRuntime sRuntime;

    /** [INCOGNITO] Intent extra of a new window: its first tab is an incognito tab. */
    static final String EXTRA_START_PRIVATE = "com.spoongecko.browser.START_PRIVATE";
    static final String EXTRA_INITIAL_URL = "com.spoongecko.browser.INITIAL_URL";
    static final String EXTRA_OPEN_MODE = "com.spoongecko.browser.OPEN_MODE";
    static final String OPEN_MODE_HISTORY_DEFAULT = "history_default";
    static final String OPEN_MODE_NEW_TAB = "new_tab";
    static final String OPEN_MODE_NEW_INCOGNITO_TAB = "new_incognito_tab";

    /**
     * [WINDOW] Every browser window that exists: the MainActivity and any BrowserWindowActivity
     * opened with "New window". They share one engine (GeckoRuntime) but each has its own tabs.
     * Main thread only.
     */
    private static final List<MainActivity> WINDOWS = new ArrayList<>();
    /**
     * [WINDOW] The window that the translation banner and the extension buttons are currently set
     * up for. Each of those exists once for the whole app, so they follow the window that was
     * last in front (see onResume); a window that is destroyed gives them up.
     */
    private static MainActivity translatorOwner;
    private static MainActivity extensionOwner;

    private TabManager tabManager;
    private GeckoView geckoView;
    private PullToRefreshLayout pullRefresh;
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
    private DragDropController dragDrop;

    BrowserPrompts getPrompts() { return prompts; }
    private FindInPageController findInPage; // [FIND] the find-in-page bar
    private TabSwitcher tabSwitcher; // [TABS] Chrome-style tab switcher
    private HistoryDialog historyDialog;   // [HISTORY] history with Newest / Oldest / Site order
    private BookmarkRepository bookmarks;  // [BOOKMARKS] Room-backed persistent bookmarks
    private BookmarksDialog bookmarksDialog;
    /** [TABS] Set once the last tab was closed from the tab switcher and the browser is exiting. */
    private boolean leavingWithNoTabs = false;
    private GeckoSession.PermissionDelegate.Callback pendingPermissionCallback;

    /** [LAN] Address waiting for the local-network permission prompt (null = none showing). */
    private String pendingLocalNetworkUrl;
    /** [LAN] Address to load when the user returns from Settings with access granted. */
    private String retryAfterSettingsUrl;
    /** [LAN] The local-network dialogs while they are up: onDestroy() closes them. */
    private AlertDialog localNetworkPrompt;
    private AlertDialog localNetworkDenied;

    /** True while this activity is being destroyed — blocks tab revival below. */
    private boolean destroying = false;

    /** [MEDIA] True between onResume and onPause. */
    private boolean resumed = false;
    /** [MEDIA] The tab that was active before the current one (its priority is lowered on a switch). */
    private Tab lastActiveTab;

    /**
     * [FULLSCREEN] The tab whose page is in fullscreen (a manga reader, a video), or null.
     * While it is set the toolbar is hidden, and Back must take the page out of fullscreen
     * instead of navigating: see leaveFullScreen().
     */
    private Tab fullScreenTab;

    private static synchronized GeckoRuntime ensureRuntime(Activity activity) {
        if (sRuntime == null) {
            // Runtime creation (and its settings) lives in ExtensionManager so
            // background update checks can create the same runtime without a UI.
            sRuntime = ExtensionManager.get().ensureRuntime(activity.getApplicationContext());
        }
        return sRuntime;
    }

    /**
     * [WINDOW] Whether this window's tabs are the ones kept on the device, to be brought back at
     * the next start. True for the main window. A window opened with "New window"
     * (BrowserWindowActivity) overrides it: it must neither read the saved tabs (they belong to
     * the main window and would open twice) nor write them (it would replace the main window's).
     * Its tabs still survive being re-created by Android; they just are not kept across a restart.
     */
    protected boolean ownsSavedTabs() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // [WINDOW] Written down before anything else, so every later step can tell whether this is
        // the first window of the process (the only one that tidies up after a previous run).
        final boolean firstWindow = WINDOWS.isEmpty();
        WINDOWS.add(this);

        // [C5] If the previous run crashed, show its stack trace first so it
        // can be copied/shared from the phone (no adb available in this setup).
        App.showLastCrashIfAny(this);

        // Fix for the startup crash (C2): content must be attached BEFORE any
        // controller looks up views; ScreenController is created exactly once.
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);
        pullRefresh = findViewById(R.id.pull_refresh);
        urlBar = findViewById(R.id.url_bar);
        searchBar = findViewById(R.id.search_bar);
        final String searchHint = BrowserSettings.homeSearchHint(this);
        searchBar.setHint(searchHint);
        searchBar.setContentDescription(searchHint);
        tabButton = findViewById(R.id.tab_button);
        tabCountText = findViewById(R.id.tab_count_text);
        forwardButton = findViewById(R.id.forward_button);
        ImageButton menuButton = findViewById(R.id.menu_button);
        ListView suggestionsList = findViewById(R.id.suggestions_list);
        extensionActions = findViewById(R.id.extension_actions);

        geckoView.setBackgroundColor(android.graphics.Color.BLACK);
        history = new HistoryStore(this);
        bookmarks = BookmarkRepository.get(this);

        screen = new ScreenController(this);
        screen.makeImmersive();
        screen.installFlashGuard();
        screen.setupWindowInsets();
        applyToolbarChrome();
        new Handler(Looper.getMainLooper()).postDelayed(screen::removeFlashGuard, 1500);

        if (pullRefresh != null) {
            pullRefresh.setCanPullCallback(() -> {
                final Tab t = tabManager == null ? null : tabManager.getActiveTab();
                return !destroying
                        && screen != null
                        && !screen.isHomePanelVisible()
                        && fullScreenTab == null
                        && t != null
                        && !t.awaitingOpen
                        && t.session.isOpen()
                        && t.url != null
                        && !t.url.isEmpty()
                        && t.scrollY <= 2;
            });
            pullRefresh.setOnRefreshListener(() -> {
                final Tab t = tabManager == null ? null : tabManager.getActiveTab();
                if (t != null && !t.awaitingOpen && t.session.isOpen()
                        && t.url != null && !t.url.isEmpty()) {
                    t.session.reload();
                } else if (pullRefresh != null) {
                    pullRefresh.setRefreshing(false);
                }
            });
        }

        suggestions = new SuggestionsController(this, urlBar, suggestionsList, history);
        suggestions.setListener(this);

        tabManager = new TabManager();
        tabManager.setListener(this);

        popups = new PopupsController(this, geckoView, menuButton, this);
        dialogs = new BrowserDialogs(this, tabManager, history);
        prompts = new BrowserPrompts(this);
        delegates = new BrowserDelegates(this, this, prompts);
        
        // [TABS] Chrome-style tab switcher, opened by the tab button.
        tabSwitcher = new TabSwitcher(this, tabManager, geckoView, new TabSwitcher.Host() {
            @Override public void openNewTab() { newTab(); }
            @Override public void tabsEdited() { persistAllTabs(); }
            @Override public boolean isHomePanelShown() { return screen.isHomePanelVisible(); }
            @Override public void closeAllTabsAndExit() { leaveWithNoTabs(); }
        });

        historyDialog = new HistoryDialog(this, history);   // [HISTORY]
        bookmarksDialog = new BookmarksDialog(this, bookmarks);   // [BOOKMARKS]
        // [TRANSLATE] Page translation. Its banner floats over the page; bound before any tab is attached.
        PageTranslator.get().bind(this, (FrameLayout) findViewById(R.id.content_container), tabManager);            
        translatorOwner = this;   // [WINDOW] the translator is set up for this window now
        // [FIND] Created before restoreTabs(): switching to the restored tab
        // (which happens inside restoreTabs) must find the controller in place.
        findInPage = new FindInPageController(this, this);

        // [MEDIA] Background playback: keep the process priority of playing pages high.
        // [WINDOW] The media controller exists once, so a change is applied to the tabs of EVERY
        // window: a page playing in a window that is not in front must keep its priority.
        registerMediaListener();

        setupBackHandler();
        setupInputHandlers();

        sRuntime = ensureRuntime(this);
        applySelectionColor();   // [SELECT] visible text-selection highlight on dark pages
        PdfSupport.enableViewer(this);   // [PDF] GeckoView ships its PDF viewer switched off
        PageTranslator.enableOffers(sRuntime);   // [TRANSLATE] the engine may offer to translate pages

        // --- Extension support -------------------------------------------------
        ExtensionManager.get().initialize(this);
        ExtensionManager.get().setHost(this);
        ExtensionManager.get().attachToolbar(extensionActions);
        extensionOwner = this;   // [WINDOW] the extension buttons are set up for this window now
        ExtensionManager.get().maybeCheckForUpdatesOnStartup();
        dragDrop = new DragDropController(
                this,
                geckoView,
                (FrameLayout) findViewById(R.id.content_container),
                screen,
                tabManager,
                prompts.getFilePicker());
        // [DOWNLOAD] leftovers of a run that was killed. Only the first window of the process may
        // do this: a window opened later would clear the notifications of downloads in progress.
        if (firstWindow) DownloadEngine.get(this).clearStaleNotifications();
        // -------------------------------------------------------------------------

        restoreTabs(savedInstanceState);

        updateTabCount();

        // [DEFAULT] The app was started by a link from another app (it is the default browser, or
        // was picked in "Open with"). Only on a fresh start: when Android re-creates the activity
        // it hands back savedInstanceState, and the same intent must not open the page again.
        if (savedInstanceState == null) {
            final String initialUrl = safeWebUrl(getIntent() != null
                    ? getIntent().getStringExtra(EXTRA_INITIAL_URL) : null);
            if (initialUrl != null) {
                navigateToUrl(initialUrl);
                updateToolbarState();
                updateTabCount();
            } else {
                openLinkFromIntent(getIntent());
            }
        }
    }

    // ------------------------------------------------------------------
    // [DEFAULT] Links from other apps
    // ------------------------------------------------------------------

    /**
     * A link arrives while the app is already running (MainActivity is singleTask, so Android
     * delivers it here instead of starting a second copy, which would restore the same tabs a
     * second time). The launcher icon, a notification or "up" navigation arrive here too, as
     * intents without a link: they are not ours to act on.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (destroying || tabManager == null) return;
        if (linkFromIntent(intent) == null) return;
        setIntent(intent);
        openLinkFromIntent(intent);
    }

    private static String safeWebUrl(String raw) {
        if (raw == null) return null;
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        final Uri uri = Uri.parse(trimmed);
        final String scheme = uri.getScheme();
        if (scheme == null) return null;
        final String lower = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(lower) && !"https".equals(lower)) return null;
        return trimmed;
    }

    /**
     * [DEFAULT] The web address of an "open this link" intent, or null for anything else. Only
     * http and https are accepted: the activity is exported, so any app can send it an intent,
     * and a javascript:, file: or content: address from outside must never be loaded.
     */
    private static String linkFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        final Uri data = intent.getData();
        if (data == null || data.getScheme() == null) return null;
        final String scheme = data.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        final String url = data.toString();
        return url.isEmpty() ? null : url;
    }

    /**
     * [DEFAULT] Opens a link from another app. An empty home tab is used as it is; otherwise the
     * link gets a tab of its own, so the pages already open are left alone.
     */
    private void openLinkFromIntent(Intent intent) {
        final String url = linkFromIntent(intent);
        if (url == null || destroying || tabManager == null) return;
        // What was open on top of the page (tab list, menu, history) is not what the user is
        // waiting for now.
        if (tabSwitcher != null) tabSwitcher.dismiss();
        if (popups != null) popups.dismissAll();
        if (historyDialog != null) historyDialog.dismiss();
        if (bookmarksDialog != null) bookmarksDialog.dismiss();

        final String mode = intent != null ? intent.getStringExtra(EXTRA_OPEN_MODE) : null;
        if (OPEN_MODE_NEW_TAB.equals(mode)) {
            openUrlInNewTab(url, false);
            return;
        }
        if (OPEN_MODE_NEW_INCOGNITO_TAB.equals(mode)) {
            openUrlInNewTab(url, true);
            return;
        }
        if (OPEN_MODE_HISTORY_DEFAULT.equals(mode)) {
            openUrlFromHistory(url);
            return;
        }

        final Tab active = tabManager.getActiveTab();
        // A tab whose session is not open (revived after its page process died, see
        // onSessionGone) cannot be loaded into: it gets the same treatment as a tab in use.
        // [INCOGNITO] A link from outside belongs in a normal tab, never in an incognito one.
        if (active == null || active.awaitingOpen || !active.session.isOpen()
                || active.isPrivate || !isActiveTabEmpty()) {
            addNewTab(null);
            suggestions.setText("");
        }
        navigateToUrl(url);
        updateToolbarState();
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
                    navigateToUrl(BrowserSettings.searchUrlForQuery(MainActivity.this, q));
                    searchBar.setText("");
                    searchBar.clearFocus();
                }
                screen.hideKeyboard();
                return true;
            }
            return false;
        });
            
        tabButton.setOnClickListener(v -> tabSwitcher.open());   // [TABS]
        forwardButton.setOnClickListener(v -> {
            Tab t = tabManager.getActiveTab();
            if (t != null && t.canGoForward) stepHistory(t, true);   // [BACK]
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
        // [FULLSCREEN] While a page is fullscreen nothing else is on screen, so Back first takes
        // the page out of fullscreen and stays on it (like other browsers). Without this, Back
        // navigated the history instead and fullscreen only ended because the page was left.
        if (fullScreenTab != null) {
            leaveFullScreen();
            return;
        }
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
        if (t != null && t.canGoBack) { stepHistory(t, false); return; }   // [BACK]
        // [BACK] Nothing left to go back to in this tab. A tab that was opened from another tab
        // (a link, window.open, "Open link in new tab") is closed and the tab that opened it is
        // shown again, as in other browsers. Any other tab shows the home page IN PLACE of its
        // first page (see goHomeInPlace): the old goHome() here added a history entry, so the
        // next Back stepped onto the page again and the exit confirmation was never reached.
        if (t != null && closeToOpener(t)) return;
        if (!screen.isHomePanelVisible()) { goHomeInPlace(); return; }
        // [BACK] The tab is at the home page with no history left. With other tabs open it is
        // closed and a remaining tab is shown (that tab's own Back then works the same way);
        // only when it is the last tab does Back ask whether to exit.
        if (t != null && closeTabShowRemaining(t)) return;
        showExitConfirmation();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBackAction(); }

    // ------------------------------------------------------------------
    // Tab lifecycle
    // ------------------------------------------------------------------
    /**
     * [SELECT] Attaches every delegate a tab needs: the normal browser delegates plus the
     * text-selection menu that has an extra "Search" item. One place, so no tab (restored,
     * opened by a link, created by an extension) can miss it.
     */
    private void attachDelegates(Tab tab) {
        delegates.attach(tab);
        tab.session.setSelectionActionDelegate(
                new SearchSelectionDelegate(this, this::searchSelectedText));
        PageTranslator.get().attach(tab);   // [TRANSLATE]
        if (dragDrop != null) dragDrop.attachSession(tab.session);
    }

    /** [SELECT] "Search" in the selection menu: the selected text is searched in a new tab. */
    private void searchSelectedText(String text) {
        if (destroying || text == null || text.isEmpty()) return;
        // A new tab like "Open link in new tab": Back on its first page returns to this tab.
        onOpenInNewTab(BrowserSettings.searchUrlForQuery(this, text));
    }

    /**
     * [SELECT] Sets Gecko's selection highlight colour. Gecko checks the "ui.highlight"
     * override before the platform value, and restyles open pages when it changes. 55% opacity
     * keeps text readable on both white and black pages: on white it gives a light blue (black
     * text on it), on black a deep blue (white text on it), and both stand out from the page.
     */
    private void applySelectionColor() {
        try {
            GeckoPreferenceController.setGeckoPref(
                    "ui.highlight", SELECTION_HIGHLIGHT_COLOR,
                    GeckoPreferenceController.PREF_BRANCH_USER)
                    .exceptionally(e -> {
                        Log.w(TAG, "could not set the text selection colour", e);
                        return null;
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "could not set the text selection colour", e);
        }
    }

    private Tab addNewTab(GeckoSession.SessionState state) {
        return addNewTab(state, false);
    }

    /**
     * @param privateMode [INCOGNITO] make it an incognito tab (only for a tab without saved state:
     *                    saved tabs are never incognito)
     */
    private Tab addNewTab(GeckoSession.SessionState state, boolean privateMode) {
        Tab tab = (state != null) ? tabManager.createTabWithState(state)
                                  : tabManager.createTab(privateMode);
        if (state == null && BrowserSettings.isDefaultDesktopMode(this)) {
            setDesktopMode(tab, true);
        }
        attachDelegates(tab);   // [SELECT]
        tab.session.open(sRuntime);
        tabManager.notifyTabsChanged();
        tabManager.notifyActiveChanged();
        return tab;
    }

    /**
     * [KILL] Opens a tab's session if it is not open yet. A session that was prepared but never
     * opened (a tab restored lazily after a restart, or revived after its page process died) is
     * opened the first time the tab is shown. When the tab has an address but no saved page state
     * (see Tab.reloadOnOpen) the address is loaded, so the tab is never left blank.
     */
    private void openSessionIfNeeded(Tab tab) {
        if (tab.session.isOpen()) return;
        tab.session.open(sRuntime);
        final String url = tab.reloadOnOpen;
        tab.reloadOnOpen = null;
        if (url != null && !url.isEmpty()) tab.session.loadUri(url);
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

        // [WINDOW] The saved tabs belong to the main window. A window opened with "New window"
        // starts empty instead, or its tabs would be the main window's tabs opened a second time.
        if (ownsSavedTabs()) {
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
        }
        // [INCOGNITO] "New incognito window": the first tab of the new window is an incognito tab.
        // When Android re-creates the window the tabs come from the Bundle above, and incognito
        // tabs are never in it. A window of incognito tabs only therefore has nothing to restore
        // and starts over.
        // [FIX] It then starts over as the incognito window it was opened as. The extra used to be
        // read only when savedInstanceState == null, so the re-created window came back as a
        // regular one, and the tabs opened in it from then on were normal tabs.
        final boolean startPrivate = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_START_PRIVATE, false);
        addNewTab(null, startPrivate);
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
        // [KILL] A tab that has an address but no page state to restore (the state was never
        // reported before the save, or could not be read back) loads its address when it is
        // opened, instead of coming back as a blank page that hides the home panel.
        if (state == null && !tab.url.isEmpty()) tab.reloadOnOpen = tab.url;
        // [DESKTOP] Must be in place BEFORE the session opens: opening restores
        // (and so loads) the saved page, which has to use the saved user agent.
        if (desktop) setDesktopMode(tab, true);
        attachDelegates(tab);   // [SELECT]
        if (openNow) {
            openSessionIfNeeded(tab);
            tabManager.notifyTabsChanged();
            tabManager.notifyActiveChanged();
        } else {
            tabManager.notifyTabsChanged();
        }
        return tab;
    }

    private void newTab() {
        newTab(false);
    }

    /** [INCOGNITO] The menu's "New incognito tab". */
    private void newIncognitoTab() {
        newTab(true);
    }

    private void newTab(boolean privateMode) {
        addNewTab(null, privateMode);
        suggestions.setText("");
        screen.showHomePanel(true);
        updateToolbarState();
        updateTabCount();
        suggestions.hide();
    }

    /**
     * [WINDOW] Opens another browser window: a new Android task (its own entry in the recent
     * apps, and its own window when the system shows apps in windows or in split screen) with its
     * own tabs, on the same engine, cookies and settings. FLAG_ACTIVITY_MULTIPLE_TASK makes every
     * call a NEW window instead of bringing an existing one forward, and LAUNCH_ADJACENT puts it
     * next to this one when this one is in split screen (it has no effect otherwise).
     *
     * @param incognito the first tab of the new window is an incognito tab
     */
    private void openNewWindow(boolean incognito) {
        openNewWindow(incognito, null);
    }

    private void openNewWindow(boolean incognito, String initialUrl) {
        if (destroying || isFinishing()) return;
        final Intent intent = new Intent(this, BrowserWindowActivity.class);
        intent.putExtra(EXTRA_START_PRIVATE, incognito);
        final String safe = safeWebUrl(initialUrl);
        if (safe != null) {
            intent.putExtra(EXTRA_INITIAL_URL, safe);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open a new window", e);
            android.widget.Toast.makeText(this, "Couldn't open a new window",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrlFromHistory(String url) {
        if (url == null || url.trim().isEmpty() || destroying || tabManager == null) return;
        final Tab active = tabManager.getActiveTab();
        final boolean onHome = screen != null && screen.isHomePanelVisible() && isActiveTabEmpty();
        if (active != null && !active.awaitingOpen && active.session.isOpen() && onHome) {
            urlBar.clearFocus();
            screen.hideKeyboard();
            suggestions.hide();
            navigateToUrl(url);
            updateToolbarState();
            updateTabCount();
        } else {
            openUrlInNewTab(url, false);
        }
    }

    private void openUrlInNewTab(String url, boolean privateMode) {
        if (url == null || url.trim().isEmpty() || destroying || tabManager == null) return;
        addNewTab(null, privateMode);
        suggestions.setText("");
        urlBar.clearFocus();
        screen.hideKeyboard();
        suggestions.hide();
        navigateToUrl(url);
        updateToolbarState();
        updateTabCount();
        persistAllTabs();
    }

    /** The menu's "Home": a normal navigation to the blank home page (Back returns to the page). */
    private void goHome() {
        Tab t = tabManager.getActiveTab();
        if (t == null) return;
        t.url = "";
        t.session.loadUri("about:blank");
        suggestions.setText("");
        screen.showHomePanel(true);
    }

    // ------------------------------------------------------------------
    // [BACK] History handling for the Back / Forward buttons
    // ------------------------------------------------------------------

    /**
     * [BACK] Back on a tab's first page: show the home page IN PLACE of it. goHome() loads
     * about:blank as a NEW history entry, which turned canGoBack true again, so the next Back
     * stepped onto the page (it looked like "forward") and the exit confirmation could never be
     * reached. Replacing the entry keeps the history clean: home panel, then Back, then the exit
     * confirmation.
     */
    private void goHomeInPlace() {
        Tab t = tabManager.getActiveTab();
        if (t == null) return;
        t.url = "";
        t.session.load(new GeckoSession.Loader().uri("about:blank")
                .flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY));
        suggestions.setText("");
        screen.showHomePanel(true);
    }

    /**
     * [BACK] One step through the tab's history. The step is remembered so that
     * onTabLocationChanged() can make the home panel follow it: landing on the blank home entry
     * shows the panel (it used to leave an empty black page), landing on a page hides it.
     */
    private void stepHistory(Tab t, boolean forward) {
        t.historyStepPending = true;
        if (forward) t.session.goForward(); else t.session.goBack();
    }

    /**
     * [BACK] Closes a tab that was opened from another tab and shows that tab again.
     *
     * @return false when this tab does not return to an opener, or the opener is gone (it was
     *         closed): the caller then treats it like any other tab.
     */
    private boolean closeToOpener(Tab t) {
        if (!t.backReturnsToOpener || t.opener == null) return false;
        final List<Tab> all = tabManager.getTabs();
        final int at = all.indexOf(t);
        final int openerAt = all.indexOf(t.opener);
        if (at < 0 || openerAt < 0) return false;
        // Switch first, so the session that is closed next is never the one attached to the view.
        tabManager.switchTo(openerAt);
        tabManager.closeTab(at);
        return true;
    }

    /**
     * [BACK] Closes a tab whose history has run out and shows one of the remaining tabs (the
     * neighbouring one, exactly as when a tab is closed from the tab list).
     *
     * @return false when this is the only tab: the caller then asks whether to exit.
     */
    private boolean closeTabShowRemaining(Tab t) {
        final List<Tab> all = tabManager.getTabs();
        final int at = all.indexOf(t);
        if (at < 0 || all.size() < 2) return false;
        tabManager.closeTab(at);
        persistAllTabs();   // coalesced save: the closed tab must not come back after a restart
        return true;
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
            navigateToUrl(BrowserSettings.searchUrlForQuery(this, input));
        }
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
            if (t != null && !urlBar.hasFocus() && !TextUtils.equals(urlBar.getText(), t.url)) {
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

    /**
     * [TABS] The last tab was closed from the tab switcher: close every tab and leave the browser
     * at once, with no question (showExitConfirmation is the question that Back asks).
     * The saved tab list is written empty, so the next start opens the home page instead of
     * bringing back the tab that was just closed.
     */
    private void leaveWithNoTabs() {
        if (destroying) return;
        leavingWithNoTabs = true;      // onTabsChanged() must not open a replacement tab
        tabManager.closeAll();
        persistAllTabsNow();
        finishAndRemoveTask();
    }                

    // ------------------------------------------------------------------
    // TabManager.Listener
    // ------------------------------------------------------------------
    @Override
    public void onTabsChanged() {
        if (destroying) return;   // fix for the destroy-revive loop (C4)
        MediaPlaybackController.get().prune();   // [MEDIA] drop media of tabs that were closed
        updateTabCount();
        if (tabSwitcher != null) tabSwitcher.refresh();   // [TABS]
        if (tabManager.count() == 0 && !leavingWithNoTabs) newTab();
    }

    @Override
    public void onActiveTabChanged(Tab tab) {
        if (tab == null) return;
        PageTranslator.get().onActiveTabChanged(tab);   // [TRANSLATE]            
        if (tabSwitcher != null) tabSwitcher.refresh();   // [TABS]
        // [FIND] The find bar belongs to the page it was opened on.
        if (findInPage != null && findInPage.isVisible()) findInPage.close();
        // [POPUP] A tab handed to GeckoView is attached by showWhenOpened() once GeckoView has opened it.
        // Opening it here would make GeckoView reject it ("Must use an unopened GeckoSession instance").
        if (tab.awaitingOpen) return;
        // [FULLSCREEN] Fullscreen belongs to one tab. When another tab is shown (a link opened a
        // new tab, the fullscreen tab was closed or crashed) the toolbar must come back, or the
        // new tab would have none and no way to reach it.
        if (fullScreenTab != null && fullScreenTab != tab) leaveFullScreen();
        // [PERF] A lazily restored tab has a session that was never opened;
        // open it the first time it is shown (open() must precede setSession()).
        // [KILL] The same goes for a tab revived after its page process died.
        if (pullRefresh != null) pullRefresh.cancelPendingPull();
        openSessionIfNeeded(tab);
        geckoView.setSession(tab.session);
        CompositorController cc = tab.session.getCompositorController();
        if (cc != null) cc.setClearColor(android.graphics.Color.BLACK);
        suggestions.hide();
        screen.showHomePanel(tab.url.isEmpty());
        updateToolbarState();
        updateTabCount();
        applyPrivacyUi(tab);   // [INCOGNITO]
        ExtensionManager.get().setActiveSession(tab.session);   // tabs.onActivated
        // [MEDIA] Process priority follows the visible tab: raise this one and lower
        // the previous one, unless that one is playing media.
        final Tab previous = lastActiveTab;
        lastActiveTab = tab;
        if (previous != null && previous != tab) updatePriority(previous);
        updatePriority(tab);
    }

    /** [INCOGNITO] The colour used for incognito things: the tab counter and the home page title. */
    private static final int INCOGNITO_ACCENT = 0xFFC8B6FF;
    /** [INCOGNITO] What the window currently shows (so nothing is redone when the kind does not change). */
    private boolean privateUiShown = false;

    private void applyToolbarChrome() {
        final boolean bottom = BrowserSettings.isBottomToolbar(this);
        if (screen != null) screen.applyToolbarPosition(bottom);
        final View bar = findViewById(R.id.url_bar_panel);
        if (bar != null) {
            if (privateUiShown) {
                bar.setBackgroundResource(bottom
                        ? R.drawable.bg_glass_url_bar_incognito_bottom
                        : R.drawable.bg_glass_url_bar_incognito);
            } else {
                bar.setBackgroundResource(bottom
                        ? R.drawable.bg_glass_url_bar_bottom
                        : R.drawable.bg_glass_url_bar);
            }
        }
    }

    /**
     * [INCOGNITO] Makes the window show what kind of tab is on screen, and protects an incognito
     * tab from being captured:
     *  - the bar at the top is tinted purple, the tab counter and the home page title turn purple,
     *    and the home page says "Incognito" instead of "SpoonGecko";
     *  - FLAG_SECURE keeps the window out of screenshots, screen recordings and the recent-apps
     *    preview while an incognito tab is on screen. It is lifted again for a normal tab.
     */
    private void applyPrivacyUi(Tab tab) {
        final boolean priv = tab != null && tab.isPrivate;
        if (priv == privateUiShown) return;
        privateUiShown = priv;
        if (priv) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        applyToolbarChrome();
        tabCountText.setTextColor(priv ? INCOGNITO_ACCENT : 0xFFFFFFFF);
        final TextView logo = findViewById(R.id.logo_text);
        if (logo != null) {
            logo.setText(priv ? "Incognito" : "SpoonGecko");
            logo.setTextColor(priv ? INCOGNITO_ACCENT : 0xB3FFFFFF);
        }
    }

    /**
     * [MEDIA] Lets the media controller (one for the whole app) tell this window about changes.
     * [WINDOW] The newest window registers; when it is destroyed another open window does.
     */
    private void registerMediaListener() {
        MediaPlaybackController.get().setListener(new MediaPlaybackController.Listener() {
            @Override public void onMediaStateChanged() { refreshAllPriorities(); }
            @Override public void onMediaStarted() { askForNotificationPermissionOnce(); }
        });
    }

    /**
     * [WINDOW] Re-evaluates the process priority of every tab of every window. Media state is
     * shared by all windows, so a change in one has to reach the tabs of the others.
     */
    private static void refreshAllPriorities() {
        for (MainActivity window : new ArrayList<>(WINDOWS)) {
            if (window.destroying || window.tabManager == null) continue;
            for (Tab t : window.tabManager.getTabs()) window.updatePriority(t);
        }
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
    @Override public void onNewIncognitoTab() { newIncognitoTab(); }   // [INCOGNITO]
    @Override public void onNewWindow() { openNewWindow(false); }        // [WINDOW]
    @Override public void onNewIncognitoWindow() { openNewWindow(true); } // [WINDOW] + [INCOGNITO]
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
        historyDialog.show(new HistoryDialog.Actions() {
            @Override public void onOpenDefault(String url) {
                openUrlFromHistory(url);
            }
            @Override public void onOpenInNewTab(String url) {
                openUrlInNewTab(url, false);
            }
            @Override public void onOpenInNewIncognitoTab(String url) {
                openUrlInNewTab(url, true);
            }
            @Override public void onOpenInNewWindow(String url) {
                openNewWindow(false, url);
            }
            @Override public void onOpenInNewIncognitoWindow(String url) {
                openNewWindow(true, url);
            }
        });
    }
    @Override public String bookmarkMenuLabel() {
        final Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t == null || screen.isHomePanelVisible()
                || !BookmarkRepository.isBookmarkable(t.url, t.title)) {
            return null;
        }
        return bookmarks.isCachedBookmarked(t.url) ? "Remove bookmark" : "Bookmark page";
    }
    @Override public void onToggleBookmark() {
        final Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t == null || !BookmarkRepository.isBookmarkable(t.url, t.title)) return;
        bookmarks.toggleBookmark(t.url, t.title, state -> {
            if (destroying || isFinishing() || state == null) return;
            android.widget.Toast.makeText(this,
                    state ? "Bookmarked" : "Bookmark removed",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
    }
    @Override public void onShowBookmarks() {
        final Tab t = tabManager == null ? null : tabManager.getActiveTab();
        final String currentUrl = (t != null && !screen.isHomePanelVisible()) ? t.url : null;
        final String currentTitle = t != null ? t.title : null;
        bookmarksDialog.show(currentUrl, currentTitle, this::navigateToUrl);
    }
    @Override public void onShowSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }
    @Override public int activeDownloadCount() {
        return DownloadEngine.get(this).getActiveCount();
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
        // [INCOGNITO] A link opened from an incognito tab opens in an incognito tab (this is also
        // what the selection menu's "Search" does), so nothing leaks into a normal tab.
        Tab t = addNewTab(null, opener != null && opener.isPrivate);
        if (opener != null && opener.desktopMode) setDesktopMode(t, true);   // [DESKTOP] keep the site type
        // [BACK] Back on this tab's first page returns to the tab the link was opened from.
        if (opener != null) { t.opener = opener; t.backReturnsToOpener = true; }
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
        // [INCOGNITO] Extension pages are always normal tabs: an extension is not active in
        // incognito tabs unless it was allowed there, and its own pages are not private.
        final Tab t = createUnopenedTab(false);
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
        if (destroying || !ownsSavedTabs()) return;   // [WINDOW] a new window never writes the saved tabs
        TabPersistence.saveSoon(this, tabManager);
    }

    /**
     * Immediate save for lifecycle moments (pause, stop, trim, exit): same
     * synchronous call, same threads, same semantics as before. Unchanged
     * tabs are skipped inside TabPersistence, so repeated calls are cheap.
     */
    private void persistAllTabsNow() {
        if (!ownsSavedTabs()) return;   // [WINDOW] the saved tabs belong to the main window only
        TabPersistence.save(this, tabManager);
    }

    @Override public void removeFlashGuard() { screen.removeFlashGuard(); }
    @Override public void setProgressBarVisible(boolean visible) {
        screen.setProgressBarVisible(visible);
        if (!visible && pullRefresh != null) pullRefresh.setRefreshing(false);
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
        // [FULLSCREEN] Remember which tab is in fullscreen so Back can take it out of it.
        fullScreenTab = fullScreen ? tabManager.getActiveTab() : null;
        screen.setFullScreenMode(fullScreen);
        PageTranslator.get().setFullScreen(fullScreen);   // [TRANSLATE]            
    }

    /**
     * [FULLSCREEN] Takes the page that is in fullscreen out of it and brings the toolbar back.
     * GeckoView never leaves fullscreen by itself (there is no Esc key on a phone): the app has
     * to ask with exitFullScreen(), and the page then reports onFullScreen(false). The toolbar is
     * restored here at once rather than waiting for that report, so it can never stay hidden;
     * the report that follows finds nothing left to do.
     */
    private void leaveFullScreen() {
        final Tab fs = fullScreenTab;
        fullScreenTab = null;
        screen.setFullScreenMode(false);
        PageTranslator.get().setFullScreen(false);   // [TRANSLATE]            
        if (fs != null && fs.session.isOpen()) {
            try {
                fs.session.exitFullScreen();
            } catch (RuntimeException e) {
                Log.w(TAG, "exitFullScreen failed", e);
            }
        }
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
    private Tab createUnopenedTab(boolean privateMode) {
        final Tab opener = tabManager.getActiveTab();
        final Tab t = tabManager.createTab(privateMode);   // [INCOGNITO]
        t.awaitingOpen = true;
        t.opener = opener;
        if (opener != null && opener.desktopMode) setDesktopMode(t, true);   // [DESKTOP] keep the site type
        attachDelegates(t);   // [SELECT]
        return t;
    }

    @Override public Tab createNewSessionTab() {
        // [INCOGNITO] GeckoView requires the new window of a page to be private exactly when the
        // page is private (the two kinds share nothing), so the kind is taken from the opener.
        final Tab current = tabManager.getActiveTab();
        final Tab t = createUnopenedTab(current != null && current.isPrivate);
        t.openedByPage = true;
        t.backReturnsToOpener = t.opener != null;   // [BACK] Back on its first page returns to the opener
        return t;
    }

    @Override public void onNewSessionReturned(Tab t) {
        showWhenOpened(t, false);
    }

    @Override public void onTabScrollChanged(Tab tab, int scrollX, int scrollY) {
        if (pullRefresh != null && tab == tabManager.getActiveTab() && scrollY > 2) {
            pullRefresh.onPageScrolledAwayFromTop();
        }
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
            // [FIX] A second request can arrive while the system dialog of the first is still up
            // (a page asking twice). It simply replaced the first callback, which was then never
            // answered: the page's first request hung until the tab was closed.
            if (pendingPermissionCallback != null) pendingPermissionCallback.reject();
            pendingPermissionCallback = callback;
            requestPermissions(permissions, REQ_ANDROID_PERMS);
        }
    }
    @Override public void showContentPermissionDialog(
            String uri, int type, org.mozilla.geckoview.GeckoResult<Integer> result) {
        // [AUTOPLAY] GeckoView asks the app about autoplay whenever a page starts media that
        // is not allowed to play by itself (no tap on the page yet): "play video" and "play
        // audio", on practically every site (ads, banner videos). It expects the app to
        // answer from its own autoplay setting, not to ask the user: the two dialogs that
        // appeared one after the other on every page load were exactly these requests.
        // Every other permission (location, notifications ...) still gets its dialog.
        if (type == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                || type == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE) {
            result.complete(BrowserSettings.autoplayAnswer(this, type));
            return;
        }
        dialogs.showContentPermissionDialog(uri, type, result);
    }

    @Override public void showMediaPermissionDialog(String uri,
            GeckoSession.PermissionDelegate.MediaSource[] video,
            GeckoSession.PermissionDelegate.MediaSource[] audio,
            GeckoSession.PermissionDelegate.MediaCallback callback) {
        dialogs.showMediaPermissionDialog(uri, video, audio, callback);
    }

    @Override public void onTabLocationChanged(Tab tab, String url) {
        // [BACK] Set by stepHistory(); consumed by the location report that ends that step.
        final boolean afterHistoryStep = tab.historyStepPending;
        tab.historyStepPending = false;
        if (tab != tabManager.getActiveTab()) return;
        // [F14] url arrives sanitized ("" for about:blank), so an empty value
        // CLEARS the bar instead of leaving "about:blank" sitting in it.
        if (!urlBar.hasFocus()) suggestions.setText(url == null ? "" : url);
        // [BACK] A Back/Forward step that lands on the blank home entry shows the home panel
        // (it used to leave an empty black page); landing on a page hides it.
        if (afterHistoryStep) screen.showHomePanel(url == null || url.isEmpty());
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
        localNetworkPrompt = new AlertDialog.Builder(this)
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
        localNetworkDenied = new AlertDialog.Builder(this)
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

    /** Closes a local-network dialog that is still up (nothing happens to one that is gone). */
    private static void closeDialog(AlertDialog d) {
        if (d != null) d.dismiss();
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
        startActivity(new Intent(this, DownloadsActivity.class));
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

    // ------------------------------------------------------------------
    // [PDF] "Download PDF" / "Save page as PDF" in the menu
    // ------------------------------------------------------------------

    /** The menu entry for the page on screen, or null when there is nothing to offer. */
    @Override public String pdfMenuLabel() {
        final Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t == null || t.url == null || screen.isHomePanelVisible()) return null;
        final String lower = t.url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        if (ErrorPages.isErrorTitle(t.title)) return null;
        return PdfSupport.looksLikePdfUrl(t.url) ? "Download PDF" : "Save page as PDF";
    }

    @Override public void onPdfMenuAction() {
        final Tab t = tabManager.getActiveTab();
        if (t == null || t.url == null || t.url.isEmpty()) return;
        if (PdfSupport.looksLikePdfUrl(t.url)) {
            onDownloadLink(t.url, null);   // the original file, through the normal downloader
        } else {
            PdfSupport.savePageAsPdf(this, sRuntime, t.session, t.title, t.url);
        }
    }

    // [TRANSLATE] "Translate..." in the menu
    @Override public String translateMenuLabel() { return PageTranslator.get().menuLabel(); }
    @Override public void onTranslateMenuAction() { PageTranslator.get().onMenuAction(); }
                
    /**
     * [KILL] A tab's page (content) process died. Either Android reclaimed it while the app sat
     * in the background (the usual case, and exactly what happens to an app that is kept in RAM:
     * the app process lives on but its page processes are the first thing the system takes
     * back), or the page crashed it.
     *
     * The old code CLOSED the tab here. After one such reclaim every tab of the app was closed
     * one by one, the last close opened an empty replacement tab, and the next save wrote that
     * empty list over the saved tabs: opening the app from its icon then showed the home page
     * with all tabs gone, and not even a restart could bring them back.
     *
     * The tab is now KEPT. It gets a fresh session that carries its last saved page state and
     * stays unopened, like a tab restored lazily after a restart; it is opened and its page
     * reloaded when it is shown (onActiveTabChanged) or when the app comes back to the front
     * (onResume). Its saved state is never touched, so the saved tab list stays complete.
     */
    @Override public void onSessionGone(Tab tab) {
        if (destroying || tab == null) return;
        if (!tabManager.getTabs().contains(tab)) return;
        final long now = SystemClock.elapsedRealtime();
        if (tab.lastDeathAt == 0L || now - tab.lastDeathAt > DEATH_WINDOW_MS) tab.deaths = 0;
        tab.lastDeathAt = now;
        tab.deaths++;
        // A page that keeps killing its process must not be reloaded for ever, and a tab that
        // GeckoView is still about to open has no state to bring back: both keep the old
        // behaviour (the tab is closed).
        if (tab.awaitingOpen || tab.deaths > MAX_QUICK_DEATHS) {
            dropGoneTab(tab);
            return;
        }
        reviveGoneTab(tab);
    }

    /** [KILL] Gives a tab whose page process died a fresh, unopened session holding its saved state. */
    private void reviveGoneTab(Tab tab) {
        // The page that was fullscreen is gone: bring the toolbar back before its session is replaced.
        if (fullScreenTab == tab) leaveFullScreen();
        final GeckoSession.SessionState state = tab.latestState;
        tabManager.replaceSession(tab, state);
        tab.priorityHigh = false;          // the new session starts at the default priority
        tab.historyStepPending = false;
        tab.reloadOnOpen = (state == null && tab.url != null && !tab.url.isEmpty()) ? tab.url : null;
        if (tab.desktopMode) DesktopMode.apply(tab.session, true);   // before the session opens
        attachDelegates(tab);
        tabManager.notifyTabsChanged();    // the tab switcher and the media bookkeeping follow
        // The tab on screen comes back at once; while the app is in the background nothing is
        // opened (that would start a page process again for nobody): onResume does it.
        if (resumed && tab == tabManager.getActiveTab()) tabManager.notifyActiveChanged();
    }

    /** [KILL] The old reaction to a dead page process: close the tab. Only the fallback now. */
    private void dropGoneTab(Tab tab) {
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
        PageTranslator.get().onPageStarted(tab);   // [TRANSLATE] what was known about the old page is void            
        if (tab == tabManager.getActiveTab() && !urlBar.hasFocus()
                && !TextUtils.equals(urlBar.getText(), url)) {
            suggestions.setText(url);
        }
        suggestions.hide();
        // [BACK] Only the tab on screen may take the home panel away: a page that starts loading
        // in a background tab used to hide the panel of the (empty) tab being shown.
        if (tab == tabManager.getActiveTab()) screen.showHomePanel(false);
        screen.removeFlashGuard();
        screen.setProgress(0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (prompts != null && prompts.handleRequestPermissionsResult(
                requestCode, permissions, grantResults)) {
            return;
        }
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
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        persistAllTabsNow();
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            clearInactiveThumbnails();
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        persistAllTabsNow();
        clearInactiveThumbnails();
    }

    private void clearInactiveThumbnails() {
        if (tabManager == null) return;
        final Tab active = tabManager.getActiveTab();
        for (Tab t : tabManager.getTabs()) {
            if (t != active) t.thumbnail = null;
        }
    }

    /**
     * [WINDOW] The translation banner and the extension buttons exist once for the whole app, so
     * they are set up for ONE window at a time: the one that was last in front. With a single
     * window this never does anything (it already owns them since onCreate). With several, a
     * window that comes back to the front takes them over, so its translate banner and extension
     * buttons work, and the previous window's are taken down.
     */
    private void claimSharedServices() {
        if (destroying || tabManager == null) return;
        if (translatorOwner != this) {
            PageTranslator.get().bind(this, (FrameLayout) findViewById(R.id.content_container), tabManager);
            translatorOwner = this;
            final Tab current = tabManager.getActiveTab();
            if (current != null) PageTranslator.get().onActiveTabChanged(current);   // show the banner of this window's page
        }
        if (extensionOwner != this) {
            ExtensionManager.get().setHost(this);
            ExtensionManager.get().attachToolbar(extensionActions);
            extensionOwner = this;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        applyToolbarChrome();
        if (searchBar != null) {
            final String hint = BrowserSettings.homeSearchHint(this);
            searchBar.setHint(hint);
            searchBar.setContentDescription(hint);
        }
        ExtensionManager.get().setCurrentActivity(this);   // for extension prompts/popups
        resumed = true;
        claimSharedServices();   // [WINDOW]
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        // [KILL] The page process of the tab on screen was reclaimed while the app was in the
        // background (see onSessionGone): it was kept as an unopened tab, so open it again now
        // and put it on screen, restoring its page. Nothing happens for a tab that is open.
        if (t != null && !t.awaitingOpen && !t.session.isOpen()) {
            tabManager.notifyActiveChanged();
        }
        if (t != null) {
            t.session.setActive(true);
            updatePriority(t);   // HIGH: it is the visible tab (see updatePriority)
        }
        if (prompts != null) prompts.onResume();
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
        if (prompts != null) prompts.onPause();
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
        // [INCOGNITO] Incognito tabs are never put in the Bundle (it is kept by the system and can
        // be written out when the process is killed). Only normal tabs are listed, and the active
        // index is the position among them; with an incognito tab on screen it is the normal tab
        // before it.
        final List<Tab> allTabs = new ArrayList<>();
        int savedActive = 0;
        {
            final List<Tab> everyTab = tabManager.getTabs();
            final int activeAt = tabManager.getActiveIndex();
            for (int i = 0; i < everyTab.size(); i++) {
                final Tab candidate = everyTab.get(i);
                if (candidate.isPrivate) {
                    if (i == activeAt) savedActive = Math.max(0, allTabs.size() - 1);
                    continue;
                }
                if (i == activeAt) savedActive = allTabs.size();
                allTabs.add(candidate);
            }
        }
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
        out.putInt(KEY_ACTIVE_TAB, savedActive);
        out.putBooleanArray(KEY_TAB_DESKTOP, desktopFlags);
    }

    /** [TTLE] Worst-case bytes one of our Bundle strings costs inside the parcel. */
    private static long entryBytes(String s) {
        if (s == null) return 8;
        return 16 + 2 * (long) s.length();
    }

    @Override protected void onDestroy() {
        destroying = true;
        // [WINDOW] This window is gone from now on. Whether it was the last one decides what may
        // be shut down below (media playback and the translator exist once for all windows).
        WINDOWS.remove(this);
        final boolean lastWindow = WINDOWS.isEmpty();
        // [PERF] A queued coalesced save must not fire after the tabs below are
        // closed (it would otherwise be able to overwrite the saved tabs).
        // [WINDOW] The queued save is the main window's; another window must not drop it.
        if (ownsSavedTabs()) TabPersistence.cancelPendingSave();
        if (popups != null) popups.dismissAll();                
        if (tabSwitcher != null) tabSwitcher.dismiss();   // [TABS]    
        if (historyDialog != null) historyDialog.dismiss();   // [HISTORY]
        if (bookmarksDialog != null) bookmarksDialog.dismiss();   // [BOOKMARKS]
        if (prompts != null) prompts.dismissAll();   // [UPLOAD] cancels a file pick that is still waiting
        if (dragDrop != null) dragDrop.destroy();
        // [FIX] A dialog that is still up when the window goes away leaks its window ("Activity
        // has leaked window" in the log). The permission and site dialogs are tracked for exactly
        // this moment but were never closed, and the two local-network ones were not even kept.
        if (dialogs != null) dialogs.dismissAll();
        closeDialog(localNetworkPrompt);
        closeDialog(localNetworkDenied);
        // [TRANSLATE] [WINDOW] Taken down only if it is set up for THIS window; another window
        // that is still open keeps (or, once in front, takes over) its own.
        if (translatorOwner == this) {
            PageTranslator.get().unbind();
            translatorOwner = null;
        }
        if (suggestions != null) suggestions.dispose();
        // [MEDIA] The tabs are about to be closed: stop the playback service and focus with them.
        // [WINDOW] Only with the last window: another window may still be playing something.
        if (lastWindow) MediaPlaybackController.get().shutdown();
        // Fix for the exit crash (C3): GeckoView.setSession() is @NonNull and
        // throws on null — the view is being torn down, nothing to detach.
        if (tabManager != null) {
            tabManager.setListener(null);   // fix for the destroy-revive loop (C4)
            tabManager.closeAll();
        }
        if (!lastWindow) {
            // The listener may have been this window's: hand it to a window that is still open,
            // and forget the media of the tabs that were just closed.
            WINDOWS.get(WINDOWS.size() - 1).registerMediaListener();
            MediaPlaybackController.get().prune();
        }
        if (history != null) history.close();
        // [WINDOW] The extension buttons and the page opened by extensions' tabs.create follow
        // the window in front; this one stops being the target.
        if (extensionOwner == this) {
            ExtensionManager.get().setHost(null);
            extensionOwner = null;
        }
        ExtensionManager.get().onActivityDestroyed(this);
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
