package com.spoongecko.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;

import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SESSION_STATES = "sessionStates";
    private static final String KEY_TAB_INDEX = "tabIndex";
    private static final String KEY_PERSISTED_TAB_COUNT = "persistedTabCount";
    private static final String KEY_PERSISTED_TAB_URL_PREFIX = "persistedTabUrl_";
    static final int MAX_TABS = 50;
    static final int MAX_PERSISTED_TABS = 10;
    private static final int REQUEST_CODE_NOTIFICATIONS = 2;
    static final String EXTRA_LOAD_URL = "load_url";
    static final String EXTRA_CLEAR_DATA = "clear_data";
    private static final Set<String> ALLOWED_SCHEMES =
            new HashSet<>(Arrays.asList("http", "https", "data", "blob", "about", "moz-extension"));
    private static final Random NEW_TAB_RANDOM = new Random();

    private final ExtensionSessionManager extensionSessionManager =
            ExtensionSessionManager.getInstance();

    private String cachedNewTabBgHex;
    private String cachedNewTabFgHex;

    private GeckoView geckoView;
    private View toolbarContainer;
    final List<GeckoSession> sessions = new ArrayList<>();
    final Map<GeckoSession, String> tabTitles = new HashMap<>();
    final Map<GeckoSession, String> tabUrls = new HashMap<>();
    private int currentTabIndex = 0;
    final Map<GeckoSession, Boolean> canGoBackMap = new HashMap<>();
    final Map<GeckoSession, Boolean> canGoForwardMap = new HashMap<>();
    final Map<GeckoSession, GeckoSession.SessionState> sessionStates = new HashMap<>();
    private final Map<GeckoSession, String> pendingLoads = new HashMap<>();
    private GeckoSession pendingPermissionSession;

    AutoCompleteTextView urlBar;
    ProgressBar progressBar;
    private MaterialButton btnBack;
    private MaterialButton btnForward;
    private MaterialButton btnReload;
    private TextView tabManagerText;
    private MaterialButton btnMenu;

    public static Context getAppContext() {
        return SpoonGeckoApplication.getAppContext();
    }

    static GeckoRuntime getGeckoRuntime() {
        return SpoonGeckoApplication.getRuntime();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SpoonGeckoApplication.setAppContext(getApplicationContext());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setBackgroundDrawable(new ColorDrawable(
                ContextCompat.getColor(this, R.color.md_theme_background)));

        setContentView(R.layout.activity_main);

        extensionSessionManager.init(this, () -> sessions);
        extensionSessionManager.setTabFactory(this::createExtensionTab);
        extensionSessionManager.setSessionCloseHandler(this::handleExtensionClose);

        geckoView = findViewById(R.id.gecko_view);
        toolbarContainer = findViewById(R.id.toolbar_container);
        urlBar = findViewById(R.id.url_bar);
        progressBar = findViewById(R.id.progress_bar);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        tabManagerText = findViewById(R.id.tab_manager);
        btnMenu = findViewById(R.id.btn_menu);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbarContainer.setPadding(bars.left, bars.top, bars.right, 0);
            geckoView.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });

        urlBar.setThreshold(1);
        urlBar.setAdapter(new SuggestionAdapter(this));

        startService(new Intent(this, BrowserService.class));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE_NOTIFICATIONS);
        }

        if (getGeckoRuntime() == null) {
            synchronized (SpoonGeckoApplication.class) {
                if (getGeckoRuntime() == null) {
                    GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                            .aboutConfigEnabled(false)
                            .consoleOutput(false)
                            .remoteDebuggingEnabled(false)
                            .extensionsProcessEnabled(true)
                            .extensionsWebAPIEnabled(true)
                            .crashHandler(CrashHandlerService.class)
                            .build();
                    SpoonGeckoApplication.setRuntime(GeckoRuntime.create(this, settings));
                }
            }
        }

        VaultSessionBinder.registerExtension(this, getGeckoRuntime());

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_SESSION_STATES)) {
            String[] stateStrings = savedInstanceState.getStringArray(KEY_SESSION_STATES);
            currentTabIndex = savedInstanceState.getInt(KEY_TAB_INDEX, 0);
            SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
            int persistedCount = prefs.getInt(KEY_PERSISTED_TAB_COUNT, 0);
            int stateCount = stateStrings != null ? stateStrings.length : 0;
            int totalTabs = Math.max(stateCount, persistedCount);
            if (totalTabs > MAX_TABS) totalTabs = MAX_TABS;
            if (totalTabs <= 0) totalTabs = 1;

            for (int i = 0; i < totalTabs; i++) {
                GeckoSession session = new GeckoSession();
                attachDelegates(session);
                sessions.add(session);
                tabTitles.put(session, getString(R.string.tab_default_title, i + 1));
                tabUrls.put(session, null);

                String stateString = (stateStrings != null && i < stateStrings.length)
                        ? stateStrings[i] : null;
                GeckoSession.SessionState state = stateString != null
                        ? GeckoSession.SessionState.fromString(stateString)
                        : null;

                if (state != null) {
                    session.restoreState(state);
                    session.open(getGeckoRuntime());
                } else {
                    session.open(getGeckoRuntime());
                    String savedUrl = prefs.getString(KEY_PERSISTED_TAB_URL_PREFIX + i, null);
                    String fallbackUrl = (savedUrl != null && !savedUrl.isEmpty())
                            ? savedUrl
                            : buildNewTabPage();
                    tabUrls.put(session, fallbackUrl);
                    pendingLoads.put(session, fallbackUrl);
                }
            }

            if (sessions.isEmpty()) {
                createNewTab(true);
            } else {
                if (currentTabIndex >= sessions.size()) currentTabIndex = sessions.size() - 1;
                if (currentTabIndex < 0) currentTabIndex = 0;
                selectTab(currentTabIndex);
            }
        } else {
            createNewTab(true);
        }

        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.getBooleanExtra(EXTRA_CLEAR_DATA, false)) {
            clearBrowsingData();
        } else if (launchIntent != null && launchIntent.getStringExtra(EXTRA_LOAD_URL) != null) {
            String url = launchIntent.getStringExtra(EXTRA_LOAD_URL);
            if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) {
                loadUrlValue(url);
            }
        }

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl();
                return true;
            }
            return false;
        });

        urlBar.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            if (selected != null) {
                loadUrlValue(selected);
            }
        });

        btnBack.setOnClickListener(v -> {
            GeckoSession session = getCurrentSession();
            if (session != null && Boolean.TRUE.equals(canGoBackMap.get(session))) {
                session.goBack();
            }
        });

        btnForward.setOnClickListener(v -> {
            GeckoSession session = getCurrentSession();
            if (session != null && Boolean.TRUE.equals(canGoForwardMap.get(session))) {
                session.goForward();
            }
        });

        btnReload.setOnClickListener(v -> {
            GeckoSession session = getCurrentSession();
            if (session != null) session.reload();
        });

        tabManagerText.setOnClickListener(v -> showTabManager());

        btnMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(MainActivity.this, btnMenu);
            popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> handleMenuItem(item));
            popup.show();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                GeckoSession session = getCurrentSession();
                if (session == null) {
                    moveTaskToBack(true);
                    return;
                }
                if (Boolean.TRUE.equals(canGoBackMap.get(session))) {
                    session.goBack();
                    return;
                }
                if (sessions.size() > 1) {
                    handleCloseRequest(session);
                    return;
                }
                confirmExit();
            }
        });

        extensionSessionManager.refresh(getGeckoRuntime());
    }

    void attachDelegates(GeckoSession session) {
        session.setContentDelegate(new TabContentDelegate(this, session));
        session.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));
        session.setNavigationDelegate(new NavigationDelegate(this, session));
        session.setProgressDelegate(new ProgressDelegate(this, session));
        session.setPermissionDelegate(new PermissionDelegate(this, session));
        extensionSessionManager.sync(session);
    }

    private GeckoSession createExtensionTab(WebExtension.CreateTabDetails details) {
        if (sessions.size() >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.tab_limit_reached, MAX_TABS),
                    Toast.LENGTH_SHORT).show();
            return null;
        }
        GeckoSession session = new GeckoSession();
        session.open(getGeckoRuntime());
        attachDelegates(session);
        sessions.add(session);
        tabTitles.put(session, getString(R.string.tab_new_title));
        tabUrls.put(session, null);
        if (details != null && Boolean.TRUE.equals(details.active)) {
            selectTab(sessions.size() - 1);
        } else {
            updateTabManagerText();
        }
        return session;
    }

    private boolean handleExtensionClose(GeckoSession session) {
        if (session == null || sessions.size() <= 1) return false;
        handleCloseRequest(session);
        return true;
    }

    static boolean isAllowedScheme(String uri) {
        if (uri == null) return false;
        String scheme = Uri.parse(uri).getScheme();
        return scheme != null && ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    private void ensureNewTabCache() {
        if (cachedNewTabBgHex != null) return;
        int bg = ContextCompat.getColor(this, R.color.md_theme_background);
        int fg = ContextCompat.getColor(this, R.color.md_theme_on_background);
        cachedNewTabBgHex = String.format("#%06X", (0xFFFFFF & bg));
        cachedNewTabFgHex = String.format("#%06X", (0xFFFFFF & fg));
    }

    private String buildNewTabPage() {
        ensureNewTabCache();
        String[] messages = getResources().getStringArray(R.array.new_tab_messages);
        String message = messages[NEW_TAB_RANDOM.nextInt(messages.length)];
        return InternalPages.newTabPage(cachedNewTabBgHex, cachedNewTabFgHex, message);
    }

    private void createNewTab(boolean select) {
        createNewTabWithUrl(buildNewTabPage(), select);
    }

    private void createNewTabWithUrl(String url, boolean select) {
        if (sessions.size() >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.tab_limit_reached, MAX_TABS),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        GeckoSession session = new GeckoSession();
        session.open(getGeckoRuntime());
        attachDelegates(session);
        sessions.add(session);
        tabTitles.put(session, getString(R.string.tab_new_title));
        tabUrls.put(session, url);
        pendingLoads.put(session, url);
        if (select) {
            selectTab(sessions.size() - 1);
        } else {
            updateTabManagerText();
        }
    }

    private void loadPendingUrl(GeckoSession session) {
        String url = pendingLoads.remove(session);
        if (url != null) session.loadUri(url);
    }

    void selectTab(int index) {
        if (index < 0 || index >= sessions.size()) return;
        GeckoSession previous = getCurrentSession();
        if (previous != null && previous != sessions.get(index)) {
            previous.setFocused(false);
            previous.setActive(false);
            previous.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
            extensionSessionManager.setTabActive(getGeckoRuntime(), previous, false);
        }

        currentTabIndex = index;
        GeckoSession selected = sessions.get(index);

        geckoView.setSession(selected);

        selected.setActive(true);
        selected.setFocused(true);
        selected.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        extensionSessionManager.setTabActive(getGeckoRuntime(), selected, true);
        updateNavigationButtons();
        updateTabManagerText();

        String url = tabUrls.get(selected);
        if (url == null || url.startsWith("data:")) {
            urlBar.setText("");
        } else {
            urlBar.setText(url);
        }

        loadPendingUrl(selected);
        geckoView.requestLayout();
    }

    private void handleCloseRequest() {
        handleCloseRequest(getCurrentSession());
    }

    void handleCloseRequest(GeckoSession closing) {
        if (closing == null) return;
        closeTab(sessions.indexOf(closing));
    }

    void closeTab(int index) {
        if (sessions.size() <= 1) {
            moveTaskToBack(true);
            return;
        }
        if (index < 0 || index >= sessions.size()) return;

        GeckoSession closing = sessions.get(index);
        boolean wasCurrent = (index == currentTabIndex);

        if (wasCurrent) {
            int nextIndex = (index + 1 < sessions.size()) ? index + 1 : index - 1;
            currentTabIndex = nextIndex;
            GeckoSession next = sessions.get(nextIndex);
            geckoView.setSession(next);
            closing.setFocused(false);
            closing.setActive(false);
            closing.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
            extensionSessionManager.setTabActive(getGeckoRuntime(), closing, false);
        }

        closing.close();
        tabTitles.remove(closing);
        tabUrls.remove(closing);
        canGoBackMap.remove(closing);
        canGoForwardMap.remove(closing);
        sessionStates.remove(closing);
        pendingLoads.remove(closing);
        sessions.remove(index);

        if (index < currentTabIndex) {
            currentTabIndex--;
        } else if (currentTabIndex >= sessions.size()) {
            currentTabIndex = sessions.size() - 1;
        }

        selectTab(currentTabIndex);
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_confirm_message)
                .setPositiveButton(R.string.exit_action, (dialog, which) -> exitApp())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void exitApp() {
        for (GeckoSession session : sessions) session.close();
        sessions.clear();
        tabTitles.clear();
        tabUrls.clear();
        canGoBackMap.clear();
        canGoForwardMap.clear();
        sessionStates.clear();
        pendingLoads.clear();
        clearPersistedTabs();
        stopService(new Intent(this, BrowserService.class));
        finishAndRemoveTask();
    }

    private String getSearchUrl(String query) {
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        String engine = prefs.getString(Prefs.KEY_SEARCH_ENGINE, "brave");
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        switch (engine) {
            case "google":
                return "https://www.google.com/search?q=" + encoded;
            case "duckduckgo":
                return "https://duckduckgo.com/?q=" + encoded;
            case "brave":
            default:
                return "https://search.brave.com/search?q=" + encoded;
        }
    }

    private String normalizeInput(String input) {
        if (input == null || input.isEmpty()) return input;
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return input;
        String scheme = Uri.parse(input).getScheme();
        if (scheme != null && !scheme.isEmpty()) return input;
        boolean isIpAddress = input.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$");
        boolean isLocalNetwork = input.startsWith("localhost") || input.contains(".local");
        if (isIpAddress || isLocalNetwork) return "http://" + input;
        if (input.contains(".")) return "https://" + input;
        return getSearchUrl(input);
    }

    private void loadUrl() {
        loadUrlValue(urlBar.getText().toString().trim());
    }

    private void loadUrlValue(String input) {
        if (input == null || input.isEmpty()) return;
        if (sessions.isEmpty() || currentTabIndex < 0 || currentTabIndex >= sessions.size()) return;
        String normalized = normalizeInput(input);
        GeckoSession session = sessions.get(currentTabIndex);
        pendingLoads.remove(session);
        tabUrls.put(session, normalized);
        session.loadUri(normalized);
        urlBar.setText(normalized);
        urlBar.clearFocus();
    }

    private void clearPersistedTabs() {
        SharedPreferences.Editor editor = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit();
        editor.remove(KEY_PERSISTED_TAB_COUNT);
        for (int i = 0; i < MAX_TABS; i++) {
            editor.remove(KEY_PERSISTED_TAB_URL_PREFIX + i);
        }
        editor.apply();
    }

    private void clearBrowsingData() {
        GeckoRuntime runtime = getGeckoRuntime();
        for (GeckoSession session : sessions) {
            session.setActive(false);
            session.close();
        }
        sessions.clear();
        tabTitles.clear();
        tabUrls.clear();
        canGoBackMap.clear();
        canGoForwardMap.clear();
        sessionStates.clear();
        pendingLoads.clear();
        currentTabIndex = 0;
        urlBar.setText("");
        tabManagerText.setText("0/0");
        btnBack.setEnabled(false);
        btnForward.setEnabled(false);
        clearPersistedTabs();
        if (runtime == null) {
            createNewTab(true);
            return;
        }
        long flags = StorageController.ClearFlags.COOKIES
                | StorageController.ClearFlags.NETWORK_CACHE
                | StorageController.ClearFlags.IMAGE_CACHE
                | StorageController.ClearFlags.DOM_STORAGES
                | StorageController.ClearFlags.AUTH_SESSIONS
                | StorageController.ClearFlags.PERMISSIONS
                | StorageController.ClearFlags.SITE_SETTINGS
                | StorageController.ClearFlags.SITE_DATA;
        runtime.getStorageController().clearData(flags).accept(
                result -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.browsing_data_cleared, Toast.LENGTH_SHORT).show();
                    createNewTab(true);
                }),
                error -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.browsing_data_clear_failed, Toast.LENGTH_SHORT).show();
                    createNewTab(true);
                })
        );
    }

    void updateNavigationButtons() {
        GeckoSession session = getCurrentSession();
        if (session == null) {
            btnBack.setEnabled(false);
            btnForward.setEnabled(false);
            return;
        }
        boolean canBack = Boolean.TRUE.equals(canGoBackMap.get(session));
        boolean canForward = Boolean.TRUE.equals(canGoForwardMap.get(session));
        btnBack.setEnabled(canBack);
        btnForward.setEnabled(canForward);
    }

    private void updateTabManagerText() {
        String text = (currentTabIndex + 1) + "/" + sessions.size();
        tabManagerText.setText(text);
    }

    private void showTabManager() {
        TabManagerHelper.show(this, sessions, tabTitles, currentTabIndex,
                new TabManagerHelper.TabActionListener() {
                    @Override
                    public void onTabSelected(int index) {
                        if (index != currentTabIndex) {
                            selectTab(index);
                        }
                    }

                    @Override
                    public void onTabClosed(int index) {
                        if (sessions.size() <= 1) return;
                        closeTab(index);
                    }

                    @Override
                    public void onNewTab() {
                        createNewTab(true);
                    }
                });
    }

    private boolean handleMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_tab) {
            createNewTab(true);
            return true;
        } else if (id == R.id.action_close_tab) {
            if (sessions.size() <= 1) {
                Toast.makeText(this, R.string.cannot_close_last_tab, Toast.LENGTH_SHORT).show();
                return true;
            }
            handleCloseRequest();
            return true;
        } else if (id == R.id.action_bookmarks) {
            startActivity(new Intent(this, BookmarksActivity.class));
            return true;
        } else if (id == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        } else if (id == R.id.action_downloads) {
            startActivity(new Intent(this, DownloadsActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            String versionName = "unknown";
            try {
                versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            new AlertDialog.Builder(this)
                    .setTitle(R.string.about_title)
                    .setMessage(getString(R.string.about_message, versionName, getGeckoViewVersion()))
                    .setPositiveButton(R.string.close, null)
                    .show();
            return true;
        } else if (id == R.id.action_extensions) {
            startActivity(new Intent(this, ExtensionsActivity.class));
            return true;
        } else if (id == R.id.action_vault) {
            startActivity(new Intent(this, VaultActivity.class));
            return true;
        } else if (id == R.id.action_exit) {
            exitApp();
            return true;
        }
        return false;
    }

    private String getGeckoViewVersion() {
        try {
            Class<?> buildConfig = Class.forName("org.mozilla.geckoview.BuildConfig");
            java.lang.reflect.Field field = buildConfig.getField("VERSION_NAME");
            String version = (String) field.get(null);
            if (version != null && !version.isEmpty()) return "GeckoView " + version;
        } catch (Exception ignored) {}
        return getString(R.string.about_gecko_fallback);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("url", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    void showContextMenu(GeckoSession.ContentDelegate.ContextElement element) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (element.linkUri != null && !element.linkUri.isEmpty()) {
            items.add(getString(R.string.context_open_link_new_tab));
            actions.add(() -> createNewTabWithUrl(element.linkUri, true));
            items.add(getString(R.string.context_copy_link));
            actions.add(() -> copyToClipboard(element.linkUri));
        }
        if (element.srcUri != null && !element.srcUri.isEmpty()) {
            items.add(getString(R.string.context_open_image_new_tab));
            actions.add(() -> createNewTabWithUrl(element.srcUri, true));
        }
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(extractContextTitle(element))
                .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private String extractContextTitle(GeckoSession.ContentDelegate.ContextElement element) {
        if (element.title != null && !element.title.isEmpty()) return element.title;
        if (element.linkUri != null && !element.linkUri.isEmpty()) return element.linkUri;
        if (element.srcUri != null && !element.srcUri.isEmpty()) return element.srcUri;
        return getString(R.string.app_name);
    }

    void setFullscreen(boolean fullscreen) {
        toolbarContainer.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), geckoView);
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            reactivateCurrentSession();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        int total = Math.min(sessions.size(), MAX_TABS);
        int stateCount = Math.min(total, MAX_PERSISTED_TABS);
        String[] stateStrings = new String[stateCount];
        for (int i = 0; i < stateCount; i++) {
            GeckoSession.SessionState state = sessionStates.get(sessions.get(i));
            stateStrings[i] = state != null ? state.toString() : null;
        }
        outState.putStringArray(KEY_SESSION_STATES, stateStrings);
        outState.putInt(KEY_TAB_INDEX, total > 0 ? Math.min(currentTabIndex, total - 1) : 0);

        SharedPreferences.Editor editor = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit();
        editor.putInt(KEY_PERSISTED_TAB_COUNT, total);
        for (int i = 0; i < total; i++) {
            String url = tabUrls.get(sessions.get(i));
            editor.putString(KEY_PERSISTED_TAB_URL_PREFIX + i, url != null ? url : "");
        }
        for (int i = total; i < MAX_TABS; i++) {
            editor.remove(KEY_PERSISTED_TAB_URL_PREFIX + i);
        }
        editor.apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reactivateCurrentSession();
        if (BuildConfig.EXTENSIONS_ENABLED && getGeckoRuntime() != null) {
            getGeckoRuntime().getWebExtensionController()
                    .setPromptDelegate(new InstallPromptDelegate(this));
        }
    }

    private void reactivateCurrentSession() {
        GeckoSession current = getCurrentSession();
        if (current == null) return;

        applyActiveState(current);
        geckoView.postDelayed(() -> {
            GeckoSession latest = getCurrentSession();
            if (latest != null) {
                applyActiveState(latest);
            }
            geckoView.requestLayout();
        }, 150);
    }

    private void applyActiveState(GeckoSession session) {
        if (session == null) return;
        geckoView.setSession(session);
        session.setActive(true);
        session.setFocused(true);
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        extensionSessionManager.setTabActive(getGeckoRuntime(), session, true);
        updateNavigationButtons();
        geckoView.requestLayout();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GeckoSession current = getCurrentSession();
        for (GeckoSession session : sessions) {
            session.setFocused(false);
            session.setActive(false);
            if (session != current) session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
        }
        if (current != null) {
            extensionSessionManager.setTabActive(getGeckoRuntime(), current, false);
        }
        if (BuildConfig.EXTENSIONS_ENABLED && getGeckoRuntime() != null) {
            getGeckoRuntime().getWebExtensionController().setPromptDelegate(null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            if (intent.getBooleanExtra(EXTRA_CLEAR_DATA, false)) {
                clearBrowsingData();
                return;
            }
            String url = intent.getStringExtra(EXTRA_LOAD_URL);
            if (url != null && currentTabIndex < sessions.size()) {
                loadUrlValue(url);
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            for (GeckoSession session : sessions) {
                session.setActive(false);
                session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
            }
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            GeckoSession current = getCurrentSession();
            for (GeckoSession session : sessions) {
                if (session != current) {
                    session.setActive(false);
                    session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        extensionSessionManager.clear();
        for (GeckoSession session : sessions) session.close();
        sessions.clear();
        tabTitles.clear();
        tabUrls.clear();
        canGoBackMap.clear();
        canGoForwardMap.clear();
        sessionStates.clear();
        pendingLoads.clear();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1) return;

        GeckoSession target = consumePendingPermissionSession();
        if (target == null) target = getCurrentSession();
        if (target != null && grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            target.reload();
        }
    }

    void requestPermissionForSession(GeckoSession session, String[] permissions, int requestCode) {
        pendingPermissionSession = session;
        requestPermissions(permissions, requestCode);
    }

    private GeckoSession consumePendingPermissionSession() {
        GeckoSession session = pendingPermissionSession;
        pendingPermissionSession = null;
        return session;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        geckoView.requestLayout();
    }

    GeckoSession getCurrentSession() {
        if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) {
            return sessions.get(currentTabIndex);
        }
        return null;
    }
}
