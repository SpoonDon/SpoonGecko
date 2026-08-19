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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
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

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SESSION_STATES = "sessionStates";
    private static final String KEY_TAB_INDEX = "tabIndex";
    private static final String KEY_PERSISTED_TAB_COUNT = "persistedTabCount";
    private static final String KEY_PERSISTED_TAB_URL_PREFIX = "persistedTabUrl_";
    private static final int MAX_TABS = 50;
    private static final int MAX_PERSISTED_TABS = 10;
    private static final int REQUEST_CODE_NOTIFICATIONS = 2;
    static final String EXTRA_LOAD_URL = "load_url";
    static final String EXTRA_CLEAR_DATA = "clear_data";
    private static final Set<String> ALLOWED_SCHEMES =
            new HashSet<>(Arrays.asList("http", "https", "data", "blob", "about", "moz-extension"));
    private static final Object sRuntimeLock = new Object();
    static volatile GeckoRuntime sGeckoRuntime;
    private static Context appContext;

    private final ExtensionSessionManager extensionSessionManager =
            ExtensionSessionManager.getInstance();

    private String cachedNewTabBgHex;
    private String cachedNewTabFgHex;
    private String cachedNewTabHtmlStart;
    private String cachedNewTabHtmlEnd;

    private GeckoView geckoView;
    private View toolbarContainer;
    private final List<GeckoSession> sessions = new ArrayList<>();
    private final Map<GeckoSession, String> tabTitles = new HashMap<>();
    private final Map<GeckoSession, String> tabUrls = new HashMap<>();
    private int currentTabIndex = 0;
    private final Map<GeckoSession, Boolean> canGoBackMap = new HashMap<>();
    private final Map<GeckoSession, Boolean> canGoForwardMap = new HashMap<>();
    private final Map<GeckoSession, GeckoSession.SessionState> sessionStates = new HashMap<>();

    private AutoCompleteTextView urlBar;
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

        getWindow().setBackgroundDrawable(new ColorDrawable(
                ContextCompat.getColor(this, R.color.md_theme_background)));

        setContentView(R.layout.activity_main);
        appContext = getApplicationContext();

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

        urlBar.setThreshold(1);
        urlBar.setAdapter(new SuggestionAdapter());

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
                        .extensionsProcessEnabled(true)
                        .extensionsWebAPIEnabled(true)
                        .crashHandler(CrashHandlerService.class)
                        .build();
                    sGeckoRuntime = GeckoRuntime.create(this, settings);
                }
            }
        }

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

                String stateString = (stateStrings != null && i < stateStrings.length) ? stateStrings[i] : null;
                GeckoSession.SessionState state = stateString != null
                        ? GeckoSession.SessionState.fromString(stateString)
                        : null;

                if (state != null) {
                    session.restoreState(state);
                    session.open(sGeckoRuntime);
                } else {
                    session.open(sGeckoRuntime);
                    String savedUrl = prefs.getString(KEY_PERSISTED_TAB_URL_PREFIX + i, null);
                    String fallbackUrl = (savedUrl != null && !savedUrl.isEmpty())
                            ? savedUrl
                            : buildNewTabPage();
                    tabUrls.put(session, fallbackUrl);
                    session.loadUri(fallbackUrl);
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

        extensionSessionManager.refresh(sGeckoRuntime);
    }

    private void attachDelegates(GeckoSession session) {
        session.setContentDelegate(new TabContentDelegate(this, session));
        session.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));
        session.setNavigationDelegate(new NavigationDelegate(this, session));
        session.setProgressDelegate(new ProgressDelegate(this, session));
        session.setPermissionDelegate(new PermissionDelegate(this));
        extensionSessionManager.sync(session);
    }

    private GeckoSession createExtensionTab(WebExtension.CreateTabDetails details) {
        if (sessions.size() >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.tab_limit_reached, MAX_TABS), Toast.LENGTH_SHORT).show();
            return null;
        }
        GeckoSession session = new GeckoSession();
        session.open(sGeckoRuntime);
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

    private class SuggestionAdapter extends ArrayAdapter<String> {
        private final List<String> values = new ArrayList<>();

        SuggestionAdapter() {
            super(MainActivity.this, android.R.layout.simple_dropdown_item_1line);
        }

        @Override
        public int getCount() {
            return values.size();
        }

        @Override
        public String getItem(int position) {
            return values.get(position);
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    if (constraint == null || constraint.length() == 0) {
                        results.values = new ArrayList<String>();
                        results.count = 0;
                    } else {
                        List<HistoryStore.Entry> entries = HistoryStore.query(
                                MainActivity.this, constraint.toString().trim(), 10);
                        List<String> urls = new ArrayList<>();
                        for (HistoryStore.Entry entry : entries) {
                            if (entry.url != null && !entry.url.isEmpty()) {
                                urls.add(entry.url);
                            }
                        }
                        results.values = urls;
                        results.count = urls.size();
                    }
                    return results;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    values.clear();
                    if (results.values != null) {
                        values.addAll((List<String>) results.values);
                    }
                    notifyDataSetChanged();
                }
            };
        }
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

    private void ensureNewTabCache() {
        if (cachedNewTabBgHex != null) return;
        int bg = ContextCompat.getColor(this, R.color.md_theme_background);
        int fg = ContextCompat.getColor(this, R.color.md_theme_on_background);
        cachedNewTabBgHex = String.format("#%06X", (0xFFFFFF & bg));
        cachedNewTabFgHex = String.format("#%06X", (0xFFFFFF & fg));
        cachedNewTabHtmlStart = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:" + cachedNewTabBgHex + ";color:" + cachedNewTabFgHex + ";font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh}"
                + "p{font-size:18px;opacity:0.55}</style></head><body><p>";
        cachedNewTabHtmlEnd = "</p></body></html>";
    }

    private String buildNewTabPage() {
        ensureNewTabCache();
        String[] messages = getResources().getStringArray(R.array.new_tab_messages);
        String message = messages[new Random().nextInt(messages.length)];
        return "data:text/html;charset=utf-8,"
                + encodeForDataUri(cachedNewTabHtmlStart + message + cachedNewTabHtmlEnd);
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
            previous.setFocused(false);
            previous.setActive(false);
            extensionSessionManager.setTabActive(sGeckoRuntime, previous, false);
        }
        currentTabIndex = index;
        GeckoSession selected = sessions.get(index);
        geckoView.setSession(selected);
        selected.setActive(true);
        selected.setFocused(true);
        extensionSessionManager.setTabActive(sGeckoRuntime, selected, true);
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
        if (index < currentTabIndex) {
            currentTabIndex--;
        } else if (currentTabIndex >= sessions.size()) {
            currentTabIndex = sessions.size() - 1;
        }
        selectTab(currentTabIndex);
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
        if (BuildConfig.EXTENSIONS_ENABLED && sGeckoRuntime != null) {
            sGeckoRuntime.getWebExtensionController()
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
        extensionSessionManager.setTabActive(sGeckoRuntime, session, true);
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
        }
        if (current != null) {
            extensionSessionManager.setTabActive(sGeckoRuntime, current, false);
        }
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
            for (GeckoSession session : sessions) session.setActive(false);
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
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && currentTabIndex < sessions.size()) {
            sessions.get(currentTabIndex).reload();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        geckoView.requestLayout();
    }

    private GeckoSession getCurrentSession() {
        if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) return sessions.get(currentTabIndex);
        return null;
    }

    private static class TabContentDelegate implements GeckoSession.ContentDelegate {
        private final WeakReference<MainActivity> activityRef;
        private final GeckoSession ownSession;

        TabContentDelegate(MainActivity activity, GeckoSession session) {
            this.activityRef = new WeakReference<>(activity);
            this.ownSession = session;
        }

        public void onTitleChange(GeckoSession session, String title) {
            MainActivity activity = activityRef.get();
            if (activity != null && session == ownSession && title != null && !title.isEmpty()) {
                activity.tabTitles.put(session, title);
            }
        }

        public void onExternalResponse(GeckoSession session, WebResponse response) {
            MainActivity activity = activityRef.get();
            if (activity == null || response == null) return;
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show());
            DownloadManager.handleDownload(activity, response);
        }

        public void onCloseRequest(GeckoSession session) {
            MainActivity activity = activityRef.get();
            if (activity == null || session != ownSession) return;
            activity.runOnUiThread(() -> activity.handleCloseRequest(session));
        }

        public void onFullScreen(GeckoSession session, boolean fullScreen) {
            MainActivity activity = activityRef.get();
            if (activity == null || session != ownSession) return;
            activity.runOnUiThread(() -> activity.setFullscreen(fullScreen));
        }

        public void onContextMenu(GeckoSession session, int screenX, int screenY, ContextElement element) {
            MainActivity activity = activityRef.get();
            if (activity == null || element == null) return;
            activity.runOnUiThread(() -> activity.showContextMenu(element));
        }
    }

    private static class NavigationDelegate implements GeckoSession.NavigationDelegate {
        private final WeakReference<MainActivity> activityRef;
        private final GeckoSession ownSession;

        NavigationDelegate(MainActivity activity, GeckoSession session) {
            this.activityRef = new WeakReference<>(activity);
            this.ownSession = session;
        }

        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            MainActivity activity = activityRef.get();
            if (activity != null && session == ownSession) {
                activity.canGoBackMap.put(session, canGoBack);
                if (session == activity.getCurrentSession()) activity.runOnUiThread(activity::updateNavigationButtons);
            }
        }

        public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            MainActivity activity = activityRef.get();
            if (activity != null && session == ownSession) {
                activity.canGoForwardMap.put(session, canGoForward);
                if (session == activity.getCurrentSession()) activity.runOnUiThread(activity::updateNavigationButtons);
            }
        }

        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,                                                  
            GeckoSession.NavigationDelegate.LoadRequest request) {        
            MainActivity activity = activityRef.get();        
            if (activity != null                
                && DownloadDispatcher.interceptNavigation(activity, session, request.uri)) {            
                return GeckoResult.fromValue(AllowOrDeny.DENY);        
            }        
            return isAllowedScheme(request.uri) ? GeckoResult.allow() : GeckoResult.fromValue(AllowOrDeny.DENY);    
        }

        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,
                                                              GeckoSession.NavigationDelegate.LoadRequest request) {
            return isAllowedScheme(request.uri) ? GeckoResult.allow() : GeckoResult.fromValue(AllowOrDeny.DENY);
        }

        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(null);
            if (activity.sessions.size() >= MAX_TABS) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, activity.getString(R.string.tab_limit_reached, MAX_TABS), Toast.LENGTH_SHORT).show());
                return GeckoResult.fromValue(null);
            }
            GeckoSession newSession = new GeckoSession();
            activity.attachDelegates(newSession);
            activity.sessions.add(newSession);
            activity.tabTitles.put(newSession, activity.getString(R.string.tab_new_title));
            activity.tabUrls.put(newSession, uri);
            activity.selectTab(activity.sessions.size() - 1);
            return GeckoResult.fromValue(newSession);
        }

        public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(null);

            if (error.code == WebRequestError.ERROR_SECURITY_BAD_CERT) {
                String host = null;
                try { URL url = new URL(uri); host = url.getHost(); } catch (Exception ignored) {}
                String safeHost = escapeHtml(host != null ? host : uri);
                String safeUri = escapeJs(uri);
                String errorPage = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;"
                        + "min-height:100vh;margin:0;background:#111319;color:#E1E2E8}"
                        + ".card{max-width:420px;padding:32px;text-align:center}"
                        + "h2{font-size:20px;margin:0 0 8px 0;color:#FFB4AB}"
                        + "p{font-size:14px;color:#C4C6D0;margin:0 0 24px 0;line-height:1.5}"
                        + ".host{font-family:monospace;word-break:break-all;color:#E1E2E8}"
                        + "button{background:#4d6bfe;color:#fff;border:none;padding:12px 24px;"
                        + "border-radius:8px;font-size:16px;cursor:pointer}"
                        + "button:hover{background:#3b54d0}"
                        + ".cancel{background:none;color:#4d6bfe;border:1px solid #4d6bfe;margin-top:12px}"
                        + ".cancel:hover{background:#2B3042}"
                        + "</style></head><body><div class='card'>"
                        + "<h2>Security Warning</h2>"
                        + "<p>The certificate for <span class='host'>" + safeHost + "</span> is not trusted.<br>"
                        + "Connecting to this site may expose your information.</p>"
                        + "<button onclick='proceed()'>Proceed (unsafe)</button><br>"
                        + "<button class='cancel' onclick='cancel()'>Go Back</button>"
                        + "<script>"
                        + "function proceed(){"
                        + "document.addCertException(true).then(function(){"
                        + "location.replace('" + safeUri + "');"
                        + "});}"
                        + "function cancel(){history.back();}"
                        + "</script></div></body></html>";
                return GeckoResult.fromValue("data:text/html;charset=utf-8,"
                        + encodeForDataUri(errorPage));
            }

            activity.runOnUiThread(() ->
                    Toast.makeText(activity, activity.getString(R.string.error_loading_page, error.getMessage()), Toast.LENGTH_LONG).show()
            );
            return GeckoResult.fromValue(null);
        }
    }

    private static class ProgressDelegate implements GeckoSession.ProgressDelegate {
        private final WeakReference<MainActivity> activityRef;
        private final GeckoSession ownSession;

        ProgressDelegate(MainActivity activity, GeckoSession session) {
            this.activityRef = new WeakReference<>(activity);
            this.ownSession = session;
        }

        public void onPageStart(GeckoSession session, String url) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.tabUrls.put(session, url);
            if (session != activity.getCurrentSession()) return;
            activity.runOnUiThread(() -> {
                activity.progressBar.setVisibility(ProgressBar.VISIBLE);
                if (url != null && url.startsWith("data:")) {
                    activity.urlBar.setText("");
                } else {
                    activity.urlBar.setText(url);
                }
            });
        }

        public void onPageStop(GeckoSession session, boolean success) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            if (success) {
                String url = activity.tabUrls.get(session);
                String title = activity.tabTitles.get(session);
                HistoryStore.record(activity, url, title);
            }
            if (session != activity.getCurrentSession()) return;
            activity.runOnUiThread(() -> activity.progressBar.setVisibility(ProgressBar.GONE));
        }

        public void onProgressChange(GeckoSession session, int progress) {
            MainActivity activity = activityRef.get();
            if (activity == null || session != activity.getCurrentSession()) return;
            activity.runOnUiThread(() -> activity.progressBar.setProgress(progress));
        }

        public void onSecurityChange(GeckoSession session,
                                     GeckoSession.ProgressDelegate.SecurityInformation securityInfo) {}

        public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState sessionState) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                activity.sessionStates.put(session, sessionState);
            }
        }
    }

    private static class PermissionDelegate implements GeckoSession.PermissionDelegate {
        private final WeakReference<MainActivity> activityRef;
        private static final int REQUEST_CODE_PERMISSIONS = 1;
        private static final Set<Integer> AUTOPLAY_PERMISSIONS = new HashSet<>(Arrays.asList(
                PERMISSION_AUTOPLAY_AUDIBLE, PERMISSION_AUTOPLAY_INAUDIBLE));

        PermissionDelegate(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public GeckoResult<Integer> onContentPermissionRequest(
                GeckoSession session, GeckoSession.PermissionDelegate.ContentPermission perm) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            if (AUTOPLAY_PERMISSIONS.contains(perm.permission)) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            if (perm.permission == PERMISSION_GEOLOCATION) {
                if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                            REQUEST_CODE_PERMISSIONS);
                    return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                }
                return promptPermission(activity, activity.getString(R.string.perm_location), perm.uri);
            }
            if (perm.permission == PERMISSION_DESKTOP_NOTIFICATION) {
                return promptPermission(activity, activity.getString(R.string.perm_notifications), perm.uri);
            }
            if (perm.permission == PERMISSION_PERSISTENT_STORAGE) {
                return promptPermission(activity, activity.getString(R.string.perm_persistent_storage), perm.uri);
            }
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }

        private GeckoResult<Integer> promptPermission(MainActivity activity, String label, String uri) {
            GeckoResult<Integer> result = new GeckoResult<>();
            String host = extractHost(uri, activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    result.complete(ContentPermission.VALUE_DENY);
                    return;
                }
                new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.permission_allow_title, label))
                        .setMessage(activity.getString(R.string.permission_message, host, label))
                        .setPositiveButton(R.string.allow, (d, w) -> result.complete(ContentPermission.VALUE_ALLOW))
                        .setNegativeButton(R.string.deny, (d, w) -> result.complete(ContentPermission.VALUE_DENY))
                        .setOnCancelListener(d -> result.complete(ContentPermission.VALUE_DENY))
                        .show();
            });
            return result;
        }

        private String extractHost(String uri, MainActivity activity) {
            if (uri == null) return activity.getString(R.string.this_site);
            String host = Uri.parse(uri).getHost();
            return host != null ? host : activity.getString(R.string.this_site);
        }

        public GeckoResult<Integer> onMediaPermissionRequest(
                GeckoSession session, String uri,
                GeckoSession.PermissionDelegate.MediaSource[] video,
                GeckoSession.PermissionDelegate.MediaSource[] audio) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            List<String> needed = new ArrayList<>();
            if (video != null && video.length > 0 &&
                    ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.CAMERA);
            }
            if (audio != null && audio.length > 0 &&
                    ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.RECORD_AUDIO);
            }
            if (!needed.isEmpty()) {
                activity.requestPermissions(needed.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
            return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
        }

        public GeckoResult<Integer> onGeckoPermissionRequest(
                GeckoSession session, String uri, int type, GeckoSession.PermissionDelegate.Callback callback) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            if (type == PERMISSION_GEOLOCATION) {
                if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                } else {
                    activity.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_PERMISSIONS);
                    return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                }
            }
            if (type == PERMISSION_AUTOPLAY_AUDIBLE ||
                    type == PERMISSION_AUTOPLAY_INAUDIBLE) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }
    }
}
