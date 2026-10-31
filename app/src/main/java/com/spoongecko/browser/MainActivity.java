package com.spoongecko.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
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
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.CompositorController;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity
        implements ComponentCallbacks2, TabManager.Listener,
                   BrowserDelegates.Host, PopupsController.Host,
                   SuggestionsController.Listener {

    private static final String TAG = "SpoonGecko";
    private static final String BRAVE_SEARCH_URL = "https://search.brave.com/search?q=";

    private static GeckoRuntime sRuntime;
    private static List<GeckoSession.SessionState> sPersistedStates;
    private static int sPersistedActiveIndex = 0;

    private TabManager tabManager;
    private GeckoView geckoView;
    private HistoryStore history;
    private TabPersistence persistence;              // [H2] async, debounced persistence

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
    private BrowserPrompts prompts;                  // [H1] JS dialogs / select / file / auth

    // [M7] One callback per request code. The old single-slot field dropped every
    // permission request but the last one, leaving orphaned callbacks.
    private final SparseArray<GeckoSession.PermissionDelegate.Callback> permissionCallbacks =
            new SparseArray<>();
    private int nextPermissionRequest = 0x5151;

    // [C4] Set first in onDestroy() so TabManager notifications fired while
    // closing sessions cannot resurrect tabs (old bug: closeAll() -> onTabsChanged
    // -> newTab() -> a brand-new GeckoSession was opened during teardown).
    private boolean destroyed = false;

    // [M1] Stored so it can be unregistered (typed Object because
    // android.window.OnBackInvokedCallback only exists on API 33+).
    private Object backCallback;

    private static synchronized GeckoRuntime ensureRuntime(Activity activity) {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                            .build())
                    .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                    // [S2] Pin the DoH endpoint explicitly. In TRR_MODE_FIRST with no
                    // URI, DNS can silently fall back to the OS (carrier) resolver.
                    .trustedRecursiveResolverUri("https://mozilla.cloudflare-dns.com/dns-query")
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

        // [C2] The old code ran `new ScreenController(this).makeImmersive()` here,
        // BEFORE setContentView(). ScreenController's constructor dereferences views
        // (findViewById(...).getPaddingTop()) that are null at that point, so the app
        // crashed on every launch. Window flags are now applied through a
        // view-independent static method; the view-dependent controller is created
        // after the content view exists.
        ScreenController.applyImmersiveWindow(getWindow());
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
        persistence = new TabPersistence(this);      // [H2]

        screen = new ScreenController(this);
        screen.installFlashGuard();
        screen.setupWindowInsets();
        new Handler(Looper.getMainLooper()).postDelayed(screen::removeFlashGuard, 1500);

        suggestions = new SuggestionsController(this, urlBar, suggestionsList, history);
        suggestions.setListener(this);

        tabManager = new TabManager();
        tabManager.setListener(this);

        prompts = new BrowserPrompts(this);         // [H1]
        popups = new PopupsController(this, geckoView, menuButton, this);
        dialogs = new BrowserDialogs(this, tabManager, history);
        delegates = new BrowserDelegates(this, this, prompts);   // [H1] attaches prompts

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
                android.window.OnBackInvokedCallback cb = this::handleBackAction;
                backCallback = cb;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            } catch (Exception e) {
                Log.w(TAG, "register back callback failed", e);
            }
        }
    }

    // [M1] Unregister on destroy; the dispatcher otherwise keeps a strong
    // reference to the callback (and through it, this activity).
    private void unregisterBackHandler() {
        if (backCallback == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback) backCallback);
            } catch (Exception ignored) {
            }
        }
        backCallback = null;
    }

    private void handleBackAction() {
        // [M1] Close the suggestion dropdown first. Previously it was ignored
        // and back always fell through to page history.
        if (suggestions != null && suggestions.isVisible()) {
            suggestions.hide();
            screen.hideKeyboard();
            return;
        }
        // [M2] Home is now an overlay on top of the current page: back hides it
        // instead of navigating. goHome() no longer loads about:blank, so the
        // back/forward stacks are never polluted with blank entries.
        Tab t = tabManager.getActiveTab();
        if (screen.isHomePanelVisible() && t != null && !t.url.isEmpty()) {
            screen.showHomePanel(false);
            return;
        }
        if (t != null && t.canGoBack) {
            t.session.goBack();
            return;
        }
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
            // [M5] disk.activeIndex is now the index *within the saved array*
            // (tabs without state are skipped when saving), so this maps to the
            // correct restored tab instead of drifting past the end.
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
        // [M2] Pure overlay: keep the page (and its history) alive underneath.
        // The old t.session.loadUri("about:blank") pushed a blank entry onto
        // the history stack on every Home press.
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
            persistence.persistNow(tabManager);    // [H2] final synchronous snapshot
            finishAndRemoveTask();
        });
    }

    // ------------------------------------------------------------------
    // TabManager.Listener
    // ------------------------------------------------------------------
    @Override
    public void onTabsChanged() {
        // [C4] Never resurrect tabs while the activity is being destroyed.
        if (destroyed) return;
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
        // [H2] Called from onSessionStateChange for every navigation/scroll/form
        // change. The old implementation synchronously serialized every tab on
        // the main thread (Parcel -> Base64 -> JSON -> SharedPreferences), which
        // froze the UI under load. Now debounced and written on a worker thread.
        if (persistence != null && tabManager != null) {
            persistence.scheduleSave(tabManager);
        }
    }

    @Override public void removeFlashGuard() { screen.removeFlashGuard(); }
    @Override public void setProgressBarVisible(boolean visible) {
        screen.setProgressBarVisible(visible);
    }
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

    // [H5] Real load progress for the progress bar (onProgressChange).
    @Override public void setLoadProgress(int progress) {
        screen.setProgress(progress);
    }

    // [H4] URL bar / home panel follow redirects and SPA navigations.
    @Override public void onTabLocationChanged(Tab tab, String url) {
        if (destroyed || tab == null || tab != tabManager.getActiveTab()) return;
        boolean blank = url == null || url.isEmpty() || "about:blank".equals(url);
        screen.showHomePanel(blank);
        if (!blank && !urlBar.hasFocus()) suggestions.setText(url);
        updateToolbarState();
    }

    // [H3] Media (camera/microphone) permission requests now go through a
    // user-visible dialog instead of being granted silently.
    @Override public void showMediaPermissionDialog(String uri,
            GeckoSession.PermissionDelegate.MediaSource[] video,
            GeckoSession.PermissionDelegate.MediaSource[] audio,
            GeckoSession.PermissionDelegate.MediaCallback callback) {
        dialogs.showMediaPermissionDialog(uri, video, audio, callback);
    }

    // [M3] Non-web schemes (mailto:, tel:, intent:, market:, ...) are handed to
    // the system instead of failing inside Gecko.
    @Override public void openExternalUri(String uri) {
        if (uri == null || destroyed) return;
        try {
            if (uri.startsWith("intent:")) {
                Intent parsed = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(parsed);
                } catch (Exception e) {
                    String fallback = parsed.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        navigateToUrl(fallback);
                        return;
                    }
                    throw e;
                }
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
            }
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    // [M3] Real downloads: ContentDelegate.onExternalResponse routes the file
    // to the system DownloadManager.
    @Override public void downloadFile(String url, String mime, String contentDisposition) {
        if (url == null || destroyed) return;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) request.setMimeType(mime);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = fileNameFor(url, contentDisposition);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            dm.enqueue(request);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
        }
    }

    private static String fileNameFor(String url, String disposition) {
        String name = null;
        if (disposition != null) {
            Matcher m = Pattern.compile("filename\\*?=\"?([^\";]+)\"?").matcher(disposition);
            if (m.find()) {
                name = m.group(1);
                int apostrophe = name.indexOf("''");      // RFC 5987: UTF-8''name
                if (apostrophe >= 0) name = name.substring(apostrophe + 2);
            }
        }
        if (name == null || name.trim().isEmpty()) {
            try {
                name = Uri.parse(url).getLastPathSegment();
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) name = "download";
        name = name.replaceAll("[/\\\\:;\"']", "_").trim();
        return name.isEmpty() ? "download" : name;
    }

    // [M9] Content process killed or crashed: the session is closed and unusable,
    // but may be recovered by open() + reload (no state preserved). Previously the
    // app just kept showing a dead view with no way to recover.
    @Override public void onSessionGone(Tab tab) {
        if (destroyed || tab == null) return;
        final String url = tab.url;
        try {
            tab.session.open(sRuntime);
            if (url != null && !url.isEmpty() && !url.startsWith("about:")) {
                tab.session.loadUri(url);
            }
            if (tab == tabManager.getActiveTab()) {
                geckoView.setSession(tab.session);
            }
            Toast.makeText(this, "Tab was killed by the system \u2014 reopened",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "session recovery failed", e);
        }
    }

    // [M7] Robust Android permission handling: one request code per callback.
    @Override public void requestAndroidPermissions(
            String[] permissions, GeckoSession.PermissionDelegate.Callback callback) {
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
            return;
        }
        final int code = nextPermissionRequest++;
        permissionCallbacks.put(code, callback);
        requestPermissions(permissions, code);
    }

    // [S3] POST_NOTIFICATIONS is declared in the manifest but was never requested;
    // site notifications silently failed on Android 13+.
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    nextPermissionRequest++);   // no Gecko callback attached
        }
    }

    @Override public void showContentPermissionDialog(
            String uri, int type, GeckoResult<Integer> result) {
        Runnable onAllowed =
                (type == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION)
                        ? this::ensureNotificationPermission
                        : null;
        dialogs.showContentPermissionDialog(uri, type, result, onAllowed);
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
        GeckoSession.PermissionDelegate.Callback callback = permissionCallbacks.get(requestCode);
        if (callback == null) return;   // e.g. the POST_NOTIFICATIONS request above
        permissionCallbacks.remove(requestCode);
        boolean granted = grantResults != null && grantResults.length > 0;
        if (granted) {
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }
        if (granted) callback.grant();
        else callback.reject();
    }

    // [H1] File picker results come back here and are forwarded to BrowserPrompts.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (prompts != null) {
            prompts.handleActivityResult(requestCode, resultCode, data);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (persistence != null && tabManager != null) persistence.persistNow(tabManager);
    }
    @Override public void onLowMemory() {
        super.onLowMemory();
        if (persistence != null && tabManager != null) persistence.persistNow(tabManager);
    }

    @Override protected void onResume() {
        super.onResume();
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(true);
            t.session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        }
    }

    @Override protected void onPause() {
        if (persistence != null && tabManager != null) persistence.persistNow(tabManager);
        Tab t = tabManager == null ? null : tabManager.getActiveTab();
        if (t != null) {
            t.session.setActive(false);
            t.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if (persistence != null && tabManager != null) persistence.persistNow(tabManager);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (tabManager == null) return;
        List<GeckoSession.SessionState> states = new ArrayList<>();
        for (Tab t : tabManager.getTabs()) {
            if (t.latestState != null) states.add(t.latestState);
        }
        sPersistedStates = states;
        sPersistedActiveIndex = tabManager.getActiveIndex();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;                                  // [C4] guard first
        unregisterBackHandler();                           // [M1]
        if (popups != null) popups.dismissAll();
        if (dialogs != null) dialogs.dismissAll();         // [M8] no leaked windows
        if (prompts != null) prompts.dismissAll();         // [M8]
        if (suggestions != null) suggestions.dispose();
        // [C3] Do NOT call geckoView.setSession(null) — the parameter is
        // @NonNull in GeckoView, and passing null crashed the app on every
        // exit. The sessions are closed below; the view is being torn down.
        if (tabManager != null) tabManager.closeAll();
        if (persistence != null) persistence.shutdown();   // [H2]
        if (history != null) history.close();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
