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
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.lang.ref.WeakReference;
import java.net.URL;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SESSION_STATES = "sessionStates";
    private static final String KEY_TAB_INDEX = "tabIndex";
    private static final String PREFS_NAME = "spoon_prefs";
    private static final String PREF_SEARCH_ENGINE = "search_engine";
    private static final String KEY_PERSISTED_TAB_COUNT = "persistedTabCount";
    private static final String KEY_PERSISTED_TAB_URL_PREFIX = "persistedTabUrl_";
    private static final int MAX_TABS = 50;
    private static final int MAX_PERSISTED_TABS = 10;
    private static final int REQUEST_CODE_NOTIFICATIONS = 2;
    static final String EXTRA_LOAD_URL = "load_url";
    static final String EXTRA_CLEAR_DATA = "clear_data";
    private static final Set<String> ALLOWED_SCHEMES =
            new HashSet<>(Arrays.asList("http", "https", "data", "blob", "about"));
    private static final Object sRuntimeLock = new Object();
    static volatile GeckoRuntime sGeckoRuntime;
    private static Context appContext;

    private String cachedNewTabBgHex;
    private String cachedNewTabFgHex;

    private GeckoView geckoView;
    private View toolbarContainer;
    private List<GeckoSession> sessions = new ArrayList<>();
    private Map<GeckoSession, String> tabTitles = new HashMap<>();
    private Map<GeckoSession, String> tabUrls = new HashMap<>();
    private int currentTabIndex = 0;
    private Map<GeckoSession, Boolean> canGoBackMap = new HashMap<>();
    private Map<GeckoSession, Boolean> canGoForwardMap = new HashMap<>();
    private Map<GeckoSession, GeckoSession.SessionState> sessionStates = new HashMap<>();

    private EditText urlBar;
    private ProgressBar progressBar;
    private MaterialButton btnBack;
    private MaterialButton btnForward;
    private MaterialButton btnReload;
    private TextView tabManagerText;
    private MaterialButton btnMenu;

    public static Context getAppContext() {
        return appContext;
    }

    static GeckoRuntime getGeckoRuntime() {
        synchronized (sRuntimeLock) {
            return sGeckoRuntime;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        appContext = getApplicationContext();

        geckoView = findViewById(R.id.gecko_view);
        toolbarContainer = findViewById(R.id.toolbar_container);
        urlBar = findViewById(R.id.url_bar);
        progressBar = findViewById(R.id.progress_bar);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        tabManagerText = findViewById(R.id.tab_manager);
        btnMenu = findViewById(R.id.btn_menu);

        startService(new Intent(this, BrowserService.class));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE_NOTIFICATIONS);
        }

        if (sGeckoRuntime == null) {
            synchronized (sRuntimeLock) {
                if (sGeckoRuntime == null) {
                    GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                            .aboutConfigEnabled(false)
                            .consoleOutput(false)
                            .remoteDebuggingEnabled(false)
                            .fissionEnabled(true)
                            .isolatedProcessEnabled(true)
                            .appZygoteProcessEnabled(true)
                            .glMsaaLevel(0)
                            .lowMemoryDetection(true)
                            .crashHandler(CrashHandlerService.class)
                            .build();
                    sGeckoRuntime = GeckoRuntime.create(this, settings);
                }
            }
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_SESSION_STATES)) {
            String[] stateStrings = savedInstanceState.getStringArray(KEY_SESSION_STATES);
            currentTabIndex = savedInstanceState.getInt(KEY_TAB_INDEX, 0);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int persistedCount = prefs.getInt(KEY_PERSISTED_TAB_COUNT, 0);
            int totalTabs = stateStrings != null ? stateStrings.length : 0;
            if (persistedCount > totalTabs) totalTabs = Math.min(persistedCount, MAX_TABS);
            if (totalTabs <= 0) totalTabs = 1;
            for (int i = 0; i < totalTabs; i++) {
                GeckoSession session = new GeckoSession();
                session.open(sGeckoRuntime);
                attachDelegates(session);
                sessions.add(session);
                tabTitles.put(session, getString(R.string.tab_default_title, i + 1));
                tabUrls.put(session, null);
                String stateString = (stateStrings != null && i < stateStrings.length) ? stateStrings[i] : null;
                if (stateString != null) {
                    GeckoSession.SessionState state = GeckoSession.SessionState.fromString(stateString);
                    if (state != null) {
                        session.restoreState(state);
                    }
                } else {
                    String savedUrl = prefs.getString(KEY_PERSISTED_TAB_URL_PREFIX + i, null);
                    if (savedUrl != null && !savedUrl.isEmpty()) {
                        tabUrls.put(session, savedUrl);
                        session.loadUri(savedUrl);
                    }
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
                String normalized = normalizeInput(url);
                tabUrls.put(sessions.get(currentTabIndex), normalized);
                sessions.get(currentTabIndex).loadUri(normalized);
                urlBar.setText(normalized);
            }
        }

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl();
                return true;
            }
            return false;
        });

        btnBack.setOnClickListener(v -> {
            GeckoSession session = sessions.get(currentTabIndex);
            if (Boolean.TRUE.equals(canGoBackMap.get(session))) session.goBack();
        });

        btnForward.setOnClickListener(v -> {
            GeckoSession session = sessions.get(currentTabIndex);
            if (Boolean.TRUE.equals(canGoForwardMap.get(session))) session.goForward();
        });

        btnReload.setOnClickListener(v -> sessions.get(currentTabIndex).reload());

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
                if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) {
                    GeckoSession session = sessions.get(currentTabIndex);
                    if (Boolean.TRUE.equals(canGoBackMap.get(session))) {
                        session.goBack();
                        return;
                    }
                }
                moveTaskToBack(true);
            }
        });
    }

    private void attachDelegates(GeckoSession session) {
        session.setContentDelegate(new TabContentDelegate(this, session));
        session.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));
        session.setNavigationDelegate(new NavigationDelegate(this, session));
        session.setProgressDelegate(new ProgressDelegate(this, session));
        session.setPermissionDelegate(new PermissionDelegate(this));
    }

    private static String encodeForDataUri(String content) {
        return URLEncoder.encode(content, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isAllowedScheme(String uri) {
        if (uri == null) return false;
        String scheme = Uri.parse(uri).getScheme();
        return scheme != null && ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("</", "<\\/");
    }

    private String buildNewTabPage() {
        if (cachedNewTabBgHex == null) {
            int bg = ContextCompat.getColor(this, R.color.md_theme_background);
            int fg = ContextCompat.getColor(this, R.color.md_theme_on_background);
            cachedNewTabBgHex = String.format("#%06X", (0xFFFFFF & bg));
            cachedNewTabFgHex = String.format("#%06X", (0xFFFFFF & fg));
        }
        String[] messages = getResources().getStringArray(R.array.new_tab_messages);
        String message = messages[new Random().nextInt(messages.length)];
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:" + cachedNewTabBgHex + ";color:" + cachedNewTabFgHex + ";font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh}"
                + "p{font-size:18px;opacity:0.55}</style></head><body><p>" + message + "</p></body></html>";
        return "data:text/html;charset=utf-8," + encodeForDataUri(html);
    }

    private void createNewTab(boolean select) {
        createNewTabWithUrl(buildNewTabPage(), select);
    }

    private void createNewTabWithUrl(String url, boolean select) {
        if (sessions.size() >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.tab_limit_reached, MAX_TABS), Toast.LENGTH_SHORT).show();
            return;
        }
        GeckoSession session = new GeckoSession();
        session.open(sGeckoRuntime);
        attachDelegates(session);
        sessions.add(session);
        tabTitles.put(session, getString(R.string.tab_new_title));
        tabUrls.put(session, url);
        if (select) {
            selectTab(sessions.size() - 1);
        } else {
            updateTabManagerText();
        }
        session.loadUri(url);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= sessions.size()) return;
        GeckoSession previous = getCurrentSession();
        if (previous != null && previous != sessions.get(index)) {
            previous.setActive(false);
        }
        currentTabIndex = index;
        GeckoSession selected = sessions.get(index);
        geckoView.setSession(selected);
        selected.setActive(true);
        updateNavigationButtons();
        updateTabManagerText();
        String url = tabUrls.get(selected);
        if (url == null || url.startsWith("data:")) {
            urlBar.setText("");
        } else {
            urlBar.setText(url);
        }
    }

    private void handleCloseRequest() {
        handleCloseRequest(getCurrentSession());
    }

    private void handleCloseRequest(GeckoSession closing) {
        if (closing == null) return;
        if (sessions.size() <= 1) {
            moveTaskToBack(true);
            return;
        }
        int index = sessions.indexOf(closing);
        if (index == -1) return;
        closing.close();
        tabTitles.remove(closing);
        tabUrls.remove(closing);
        canGoBackMap.remove(closing);
        canGoForwardMap.remove(closing);
        sessionStates.remove(closing);
        sessions.remove(index);
        if (currentTabIndex >= sessions.size()) currentTabIndex = sessions.size() - 1;
        selectTab(currentTabIndex);
    }

    private String getSearchUrl(String query) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String engine = prefs.getString(PREF_SEARCH_ENGINE, "brave");
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
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) return;
        if (sessions.isEmpty() || currentTabIndex < 0 || currentTabIndex >= sessions.size()) return;
        input = normalizeInput(input);
        sessions.get(currentTabIndex).loadUri(input);
        urlBar.clearFocus();
    }

    private void clearPersistedTabs() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.remove(KEY_PERSISTED_TAB_COUNT);
        for (int i = 0; i < MAX_TABS; i++) {
            editor.remove(KEY_PERSISTED_TAB_URL_PREFIX + i);
        }
        editor.apply();
    }

    private void clearBrowsingData() {
        GeckoRuntime runtime = sGeckoRuntime;
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
        currentTabIndex = 0;
        urlBar.setText("");
        tabManagerText.setText("0/0");
        btnBack.setEnabled(false);
        btnForward.setEnabled(false);
        geckoView.setSession(null);
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
                    Toast.makeText(this, R.string.browsing_data_cleared, Toast.LENGTH_SHORT).show();
                    createNewTab(true);
                }),
                error -> runOnUiThread(() -> {
                    Toast.makeText(this, R.string.browsing_data_clear_failed, Toast.LENGTH_SHORT).show();
                    createNewTab(true);
                })
        );
    }

    private void updateNavigationButtons() {
        GeckoSession session = sessions.get(currentTabIndex);
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
                        GeckoSession closing = sessions.get(index);
                        closing.close();
                        tabTitles.remove(closing);
                        tabUrls.remove(closing);
                        canGoBackMap.remove(closing);
                        canGoForwardMap.remove(closing);
                        sessionStates.remove(closing);
                        sessions.remove(index);

                        if (index < currentTabIndex) {
                            currentTabIndex--;
                        } else if (index >= currentTabIndex) {
                            currentTabIndex = Math.min(currentTabIndex, sessions.size() - 1);
                        }
                        selectTab(currentTabIndex);
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
            Toast.makeText(this, R.string.bookmarks_coming_soon, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_history) {
            Toast.makeText(this, R.string.history_coming_soon, Toast.LENGTH_SHORT).show();
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
        } else if (id == R.id.action_exit) {
            for (GeckoSession session : sessions) session.close();
            sessions.clear();
            tabTitles.clear();
            tabUrls.clear();
            canGoBackMap.clear();
            canGoForwardMap.clear();
            sessionStates.clear();
            clearPersistedTabs();
            stopService(new Intent(this, BrowserService.class));
            finishAndRemoveTask();
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

    private void showContextMenu(GeckoSession.ContentDelegate.ContextElement element) {
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

    private void setFullscreen(boolean fullscreen) {
        toolbarContainer.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), geckoView);
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
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

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
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
        GeckoSession current = getCurrentSession();
        if (current != null) current.setActive(true);
        if (BuildConfig.EXTENSIONS_ENABLED && sGeckoRuntime != null) {
            sGeckoRuntime.getWebExtensionController()
                    .setPromptDelegate(new InstallPromptDelegate(this));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (GeckoSession session : sessions) session.setActive(false);
        if (BuildConfig.EXTENSIONS_ENABLED && sGeckoRuntime != null) {
            sGeckoRuntime.getWebExtensionController().setPromptDelegate(null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            if (intent.getBooleanExtra(EXTRA_CLEAR_DATA, false)) {
               
