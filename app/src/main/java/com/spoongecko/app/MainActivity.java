package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebRequestError;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SESSION_STATES = "sessionStates";
    private static final String KEY_TAB_INDEX = "tabIndex";
    private static final String PREFS_NAME = "spoon_prefs";
    private static final String PREF_SEARCH_ENGINE = "search_engine";
    private static final int MAX_TABS = 50;
    private static final int MAX_PERSISTED_TABS = 10;
    static final String EXTRA_LOAD_URL = "load_url";
    private static final String[] NEW_TAB_MESSAGES = {
            "Hello there !",
            "Happy browsing !",
            "Explore away !",
            "Stay curious."
    };
    private static final Object sRuntimeLock = new Object();
    static volatile GeckoRuntime sGeckoRuntime;
    private static Context appContext;

    // Cached new-tab page template colors (computed once per instance)
    private String cachedNewTabBgHex;
    private String cachedNewTabFgHex;

    public static Context getAppContext() {
        return appContext;
    }

    static GeckoRuntime getGeckoRuntime() {
        synchronized (sRuntimeLock) {
            return sGeckoRuntime;
        }
    }

    private GeckoView geckoView;
    private List<GeckoSession> sessions = new ArrayList<>();
    private Map<GeckoSession, String> tabTitles = new HashMap<>();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        appContext = getApplicationContext();

        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);
        progressBar = findViewById(R.id.progress_bar);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        tabManagerText = findViewById(R.id.tab_manager);
        btnMenu = findViewById(R.id.btn_menu);

        startService(new Intent(this, BrowserService.class));

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
            for (int i = 0; i < stateStrings.length; i++) {
                GeckoSession session = new GeckoSession();
                session.open(sGeckoRuntime);
                session.setNavigationDelegate(new NavigationDelegate(this, session));
                session.setProgressDelegate(new ProgressDelegate(this, session));
                session.setPermissionDelegate(new PermissionDelegate(this));
                session.setContentDelegate(new TabContentDelegate(this, session));
                sessions.add(session);
                tabTitles.put(session, "Tab " + (i + 1));
                if (stateStrings[i] != null) {
                    GeckoSession.SessionState state = GeckoSession.SessionState.fromString(stateStrings[i]);
                    if (state != null) {
                        session.restoreState(state);
                    }
                }
            }
        } else {
            createNewTab(true);
        }

        if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) {
            geckoView.setSession(sessions.get(currentTabIndex));
            updateNavigationButtons();
            updateTabManagerText();
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
                GeckoSession session = sessions.get(currentTabIndex);
                if (Boolean.TRUE.equals(canGoBackMap.get(session))) {
                    session.goBack();
                }
            }
        });
    }

    private static String encodeForDataUri(String content) {
        return URLEncoder.encode(content, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String buildNewTabPage() {
        if (cachedNewTabBgHex == null) {
            int bg = ContextCompat.getColor(this, R.color.md_theme_background);
            int fg = ContextCompat.getColor(this, R.color.md_theme_on_background);
            cachedNewTabBgHex = String.format("#%06X", (0xFFFFFF & bg));
            cachedNewTabFgHex = String.format("#%06X", (0xFFFFFF & fg));
        }
        String message = NEW_TAB_MESSAGES[new Random().nextInt(NEW_TAB_MESSAGES.length)];
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:" + cachedNewTabBgHex + ";color:" + cachedNewTabFgHex + ";font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh}"
                + "p{font-size:18px;opacity:0.55}</style></head><body><p>" + message + "</p></body></html>";
        return "data:text/html;charset=utf-8," + encodeForDataUri(html);
    }

    private void createNewTab(boolean select) {
        if (sessions.size() >= MAX_TABS) {
            Toast.makeText(this, "Maximum tab limit reached (" + MAX_TABS + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        GeckoSession session = new GeckoSession();
        session.open(sGeckoRuntime);
        session.setNavigationDelegate(new NavigationDelegate(this, session));
        session.setProgressDelegate(new ProgressDelegate(this, session));
        session.setPermissionDelegate(new PermissionDelegate(this));
        session.setContentDelegate(new TabContentDelegate(this, session));
        sessions.add(session);
        tabTitles.put(session, "New Tab");
        if (select) {
            currentTabIndex = sessions.size() - 1;
            geckoView.setSession(session);
            updateNavigationButtons();
            urlBar.setText("");
        }
        updateTabManagerText();
        session.loadUri(buildNewTabPage());
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

    private void loadUrl() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) return;

        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            boolean isIpAddress = input.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$");
            boolean isLocalNetwork = input.startsWith("localhost") || input.contains(".local");
            if (isIpAddress || isLocalNetwork) {
                input = "http://" + input;
            } else if (input.contains(".")) {
                input = "https://" + input;
            } else {
                input = getSearchUrl(input);
            }
        }
        sessions.get(currentTabIndex).loadUri(input);
        urlBar.clearFocus();
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
                            currentTabIndex = index;
                            geckoView.setSession(sessions.get(index));
                            updateNavigationButtons();
                            updateTabManagerText();
                            urlBar.setText("");
                        }
                    }

                    @Override
                    public void onTabClosed(int index) {
                        if (sessions.size() <= 1) return;
                        GeckoSession closing = sessions.get(index);
                        closing.close();
                        tabTitles.remove(closing);
                        canGoBackMap.remove(closing);
                        canGoForwardMap.remove(closing);
                        sessionStates.remove(closing);
                        sessions.remove(index);

                        if (index < currentTabIndex) {
                            currentTabIndex--;
                        } else if (index == currentTabIndex) {
                            currentTabIndex = Math.min(currentTabIndex, sessions.size() - 1);
                        }

                        geckoView.setSession(sessions.get(currentTabIndex));
                        updateNavigationButtons();
                        updateTabManagerText();
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
                Toast.makeText(this, "Cannot close the last tab", Toast.LENGTH_SHORT).show();
                return true;
            }
            GeckoSession closing = sessions.get(currentTabIndex);
            closing.close();
            tabTitles.remove(closing);
            canGoBackMap.remove(closing);
            canGoForwardMap.remove(closing);
            sessionStates.remove(closing);
            sessions.remove(currentTabIndex);
            if (currentTabIndex >= sessions.size()) currentTabIndex = sessions.size() - 1;
            geckoView.setSession(sessions.get(currentTabIndex));
            updateNavigationButtons();
            updateTabManagerText();
            return true;
        } else if (id == R.id.action_bookmarks) {
            Toast.makeText(this, "Bookmarks coming soon", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_history) {
            Toast.makeText(this, "History coming soon", Toast.LENGTH_SHORT).show();
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
            String gvVersion = "GeckoView 153.0.20260803132010";
            new AlertDialog.Builder(this)
                    .setTitle("SpoonGecko")
                    .setMessage("App Version: " + versionName + "\n"
                            + "GeckoView: " + gvVersion + "\n\n"
                            + "A minimal, privacy-first GeckoView browser.\n"
                            + "No telemetry. No tracking.\n\n"
                            + "- with love, Plaban.")
                    .setPositiveButton("Close", null)
                    .show();
            return true;
        } else if (id == R.id.action_extensions) {
            startActivity(new Intent(this, ExtensionsActivity.class));
            return true;
        } else if (id == R.id.action_exit) {
            for (GeckoSession session : sessions) session.close();
            sessions.clear();
            tabTitles.clear();
            canGoBackMap.clear();
            canGoForwardMap.clear();
            sessionStates.clear();
            stopService(new Intent(this, BrowserService.class));
            finishAndRemoveTask();
            return true;
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        int count = Math.min(sessions.size(), MAX_PERSISTED_TABS);
        String[] stateStrings = new String[count];
        for (int i = 0; i < count; i++) {
            GeckoSession.SessionState state = sessionStates.get(sessions.get(i));
            stateStrings[i] = state != null ? state.toString() : null;
        }
        outState.putStringArray(KEY_SESSION_STATES, stateStrings);
        outState.putInt(KEY_TAB_INDEX, count > 0 ? Math.min(currentTabIndex, count - 1) : 0);
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
            String url = intent.getStringExtra(EXTRA_LOAD_URL);
            if (url != null && currentTabIndex < sessions.size()) {
                sessions.get(currentTabIndex).loadUri(url);
                urlBar.setText(url);
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            for (GeckoSession session : sessions) session.setActive(false);
        }
    }

    @Override
    protected void onDestroy() {
        for (GeckoSession session : sessions) session.close();
        sessions.clear();
        tabTitles.clear();
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
            return GeckoResult.allow();
        }

        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,
                                                              GeckoSession.NavigationDelegate.LoadRequest request) {
            return GeckoResult.allow();
        }

        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (activity.sessions.size() >= MAX_TABS) {
                        Toast.makeText(activity, "Maximum tab limit reached (" + MAX_TABS + ")", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    GeckoSession newSession = new GeckoSession();
                    newSession.open(sGeckoRuntime);
                    newSession.setNavigationDelegate(new NavigationDelegate(activity, newSession));
                    newSession.setProgressDelegate(new ProgressDelegate(activity, newSession));
                    newSession.setPermissionDelegate(new PermissionDelegate(activity));
                    newSession.setContentDelegate(new TabContentDelegate(activity, newSession));
                    activity.sessions.add(newSession);
                    activity.tabTitles.put(newSession, "New Tab");
                    activity.currentTabIndex = activity.sessions.size() - 1;
                    activity.geckoView.setSession(newSession);
                    activity.updateNavigationButtons();
                    activity.updateTabManagerText();
                    newSession.loadUri(uri);
                });
            }
            return GeckoResult.fromValue(null);
        }

        public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(null);

            if (error.code == WebRequestError.ERROR_SECURITY_BAD_CERT) {
                String host = null;
                try { URL url = new URL(uri); host = url.getHost(); } catch (Exception ignored) {}
                final String finalHost = host != null ? host : uri;
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
                        + "<p>The certificate for <span class='host'>" + finalHost + "</span> is not trusted.<br>"
                        + "Connecting to this site may expose your information.</p>"
                        + "<button onclick='proceed()'>Proceed (unsafe)</button><br>"
                        + "<button class='cancel' onclick='cancel()'>Go Back</button>"
                        + "<script>"
                        + "function proceed(){"
                        + "document.addCertException(true).then(function(){"
                        + "location.replace('" + uri.replace("\\", "\\\\").replace("'", "\\'") + "');"
                        + "});}"
                        + "function cancel(){history.back();}"
                        + "</script></div></body></html>";
                return GeckoResult.fromValue("data:text/html;charset=utf-8,"
                        + encodeForDataUri(errorPage));
            }

            activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Error loading page: " + error.getMessage(), Toast.LENGTH_LONG).show()
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
            if (activity == null || session != activity.getCurrentSession()) return;
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
            if (activity == null || session != activity.getCurrentSession()) return;
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

        PermissionDelegate(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public GeckoResult<Integer> onContentPermissionRequest(
                GeckoSession session, GeckoSession.PermissionDelegate.ContentPermission perm) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
        }

        public GeckoResult<Integer> onMediaPermissionRequest(
                GeckoSession session, String uri,
                GeckoSession.PermissionDelegate.MediaSource[] video,
                GeckoSession.PermissionDelegate.MediaSource[] audio) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
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
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            }
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
        }

        public GeckoResult<Integer> onGeckoPermissionRequest(
                GeckoSession session, String uri, int type, GeckoSession.PermissionDelegate.Callback callback) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            if (type == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                } else {
                    activity.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE_PERMISSIONS);
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                }
            }
            if (type == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    type == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
            }
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
        }
    }

    private static class InstallPromptDelegate implements WebExtensionController.PromptDelegate {
        private final WeakReference<MainActivity> activityRef;

        InstallPromptDelegate(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public GeckoResult<AllowOrDeny> onInstallPrompt(WebExtension extension) {
            GeckoResult<AllowOrDeny> result = new GeckoResult<>();
            MainActivity activity = activityRef.get();
            if (activity == null) {
                result.complete(AllowOrDeny.DENY);
                return result;
            }
            String name = (extension.metaData != null && extension.metaData.name != null
                    && !extension.metaData.name.isEmpty())
                    ? extension.metaData.name
                    : (extension.id != null ? extension.id : "this extension");
            activity.runOnUiThread(() ->
                    new AlertDialog.Builder(activity)
                           .setTitle("Install Extension?")
                           .setMessage("Add \"" + name + "\" to SpoonGecko?")
                           .setPositiveButton("Install", (d, w) -> result.complete(AllowOrDeny.ALLOW))
                           .setNegativeButton("Cancel", (d, w) -> result.complete(AllowOrDeny.DENY))
                           .setOnCancelListener(d -> result.complete(AllowOrDeny.DENY))
                           .show());
            return result;
        }
    }
}
