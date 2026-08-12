package com.spoongecko.app;

import android.Manifest;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SESSION_STATES = "sessionStates";
    private static final String KEY_TAB_INDEX = "tabIndex";
    private static GeckoRuntime sGeckoRuntime;

    private GeckoView geckoView;
    private List<GeckoSession> sessions = new ArrayList<>();
    private int currentTabIndex = 0;
    private Map<GeckoSession, Boolean> canGoBackMap = new HashMap<>();
    private Map<GeckoSession, Boolean> canGoForwardMap = new HashMap<>();
    private Map<GeckoSession, GeckoSession.SessionState> sessionStates = new HashMap<>();

    private EditText urlBar;
    private MaterialButton btnGo;
    private ProgressBar progressBar;
    private MaterialButton btnBack;
    private MaterialButton btnForward;
    private MaterialButton btnRefresh;
    private MaterialButton btnStop;
    private MaterialButton btnMenu;
    private TabLayout tabLayout;
    private MaterialButton btnNewTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);
        btnGo = findViewById(R.id.btn_go);
        progressBar = findViewById(R.id.progress_bar);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnStop = findViewById(R.id.btn_stop);
        btnMenu = findViewById(R.id.btn_menu);
        tabLayout = findViewById(R.id.tab_layout);
        btnNewTab = findViewById(R.id.btn_new_tab);

        startService(new Intent(this, BrowserService.class));

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
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                    .build();
            sGeckoRuntime = GeckoRuntime.create(this, settings);
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
                sessions.add(session);
                if (stateStrings[i] != null) {
                    GeckoSession.SessionState state = GeckoSession.SessionState.fromString(stateStrings[i]);
                    if (state != null) {
                        session.restoreState(state);
                    }
                }
                addTabToLayout("Tab " + (i + 1), i == currentTabIndex);
            }
        } else {
            createNewTab("https://www.mozilla.org", true);
        }

        if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) {
            geckoView.setSession(sessions.get(currentTabIndex));
            updateNavigationButtons();
        }

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                if (pos >= 0 && pos < sessions.size() && pos != currentTabIndex) {
                    currentTabIndex = pos;
                    geckoView.setSession(sessions.get(pos));
                    updateNavigationButtons();
                    urlBar.setText("");
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnNewTab.setOnClickListener(v -> createNewTab("https://www.mozilla.org", false));

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl();
                return true;
            }
            return false;
        });

        btnGo.setOnClickListener(v -> loadUrl());

        btnBack.setOnClickListener(v -> {
            GeckoSession session = sessions.get(currentTabIndex);
            if (Boolean.TRUE.equals(canGoBackMap.get(session))) session.goBack();
        });

        btnForward.setOnClickListener(v -> {
            GeckoSession session = sessions.get(currentTabIndex);
            if (Boolean.TRUE.equals(canGoForwardMap.get(session))) session.goForward();
        });

        btnRefresh.setOnClickListener(v -> sessions.get(currentTabIndex).reload());
        btnStop.setOnClickListener(v -> sessions.get(currentTabIndex).stop());

        btnMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(MainActivity.this, btnMenu);
            popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> handleMenuItem(item));
            popup.show();
        });
    }

    private void createNewTab(String url, boolean select) {
        GeckoSession session = new GeckoSession();
        session.open(sGeckoRuntime);
        session.setNavigationDelegate(new NavigationDelegate(this, session));
        session.setProgressDelegate(new ProgressDelegate(this, session));
        session.setPermissionDelegate(new PermissionDelegate(this));
        sessions.add(session);
        addTabToLayout("Tab " + sessions.size(), select);
        if (select) {
            currentTabIndex = sessions.size() - 1;
            geckoView.setSession(session);
            updateNavigationButtons();
        }
        if (url != null) session.loadUri(url);
    }

    private void addTabToLayout(String title, boolean select) {
        TabLayout.Tab tab = tabLayout.newTab().setText(title);
        tabLayout.addTab(tab, select);
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
                input = "https://duckduckgo.com/?q=" + input.replace(" ", "+");
            }
        }
        sessions.get(currentTabIndex).loadUri(input);
        urlBar.clearFocus();
    }

    private void updateNavigationButtons() {
        GeckoSession session = sessions.get(currentTabIndex);
        btnBack.setEnabled(Boolean.TRUE.equals(canGoBackMap.get(session)));
        btnForward.setEnabled(Boolean.TRUE.equals(canGoForwardMap.get(session)));
    }

    private boolean handleMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_tab) {
            createNewTab("https://www.mozilla.org", false);
            return true;
        } else if (id == R.id.action_close_tab) {
            if (sessions.size() <= 1) {
                Toast.makeText(this, "Cannot close the last tab", Toast.LENGTH_SHORT).show();
                return true;
            }
            GeckoSession closing = sessions.get(currentTabIndex);
            closing.close();
            sessions.remove(currentTabIndex);
            tabLayout.removeTabAt(currentTabIndex);
            if (currentTabIndex >= sessions.size()) currentTabIndex = sessions.size() - 1;
            geckoView.setSession(sessions.get(currentTabIndex));
            updateNavigationButtons();
            return true;
        } else if (id == R.id.action_bookmarks) {
            Toast.makeText(this, "Bookmarks coming soon", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_history) {
            Toast.makeText(this, "History coming soon", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_downloads) {
            Toast.makeText(this, "Downloads coming soon", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_find_in_page) {
            Toast.makeText(this, "Find in page coming soon", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_settings) {
            Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        GeckoSession session = sessions.get(currentTabIndex);
        if (Boolean.TRUE.equals(canGoBackMap.get(session))) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        String[] stateStrings = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            GeckoSession.SessionState state = sessionStates.get(sessions.get(i));
            stateStrings[i] = state != null ? state.toString() : null;
        }
        outState.putStringArray(KEY_SESSION_STATES, stateStrings);
        outState.putInt(KEY_TAB_INDEX, currentTabIndex);
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (GeckoSession session : sessions) session.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (GeckoSession session : sessions) session.setActive(false);
        if (currentTabIndex < sessions.size()) sessions.get(currentTabIndex).setActive(true);
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
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && currentTabIndex < sessions.size()) {
            sessions.get(currentTabIndex).reload();
        }
    }

    private static class NavigationDelegate implements GeckoSession.NavigationDelegate {
        private final WeakReference<MainActivity> activityRef;
        private final GeckoSession ownSession;
        private final Set<String> handledHosts = new HashSet<>();

        NavigationDelegate(MainActivity activity, GeckoSession session) {
            this.activityRef = new WeakReference<>(activity);
            this.ownSession = session;
        }

        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            MainActivity activity = activityRef.get();
            if (activity != null && session == ownSession) {
                activity.canGoBackMap.put(session, canGoBack);
                if (session == activity.getCurrentSession()) activity.updateNavigationButtons();
            }
        }

        public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            MainActivity activity = activityRef.get();
            if (activity != null && session == ownSession) {
                activity.canGoForwardMap.put(session, canGoForward);
                if (session == activity.getCurrentSession()) activity.updateNavigationButtons();
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
                activity.runOnUiThread(() -> activity.createNewTab(uri, true));
            }
            return GeckoResult.fromValue(null);
        }

        public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
            MainActivity activity = activityRef.get();
            if (activity == null) {
                return GeckoResult.fromValue(null);
            }

            if (error.code == WebRequestError.ERROR_SECURITY_BAD_CERT) {
                String host = null;
                try {
                    URL url = new URL(uri);
                    host = url.getHost();
                } catch (Exception ignored) {}

                final String finalHost = host != null ? host : uri;
                String errorPage = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;"
                        + "min-height:100vh;margin:0;background:#fff;color:#0d0d0d}"
                        + ".card{max-width:420px;padding:32px;text-align:center}"
                        + "h2{font-size:20px;margin:0 0 8px 0;color:#c00}"
                        + "p{font-size:14px;color:#555;margin:0 0 24px 0;line-height:1.5}"
                        + ".host{font-family:monospace;word-break:break-all;color:#0d0d0d}"
                        + "button{background:#4d6bfe;color:#fff;border:none;padding:12px 24px;"
                        + "border-radius:8px;font-size:16px;cursor:pointer}"
                        + "button:hover{background:#3b54d0}"
                        + ".cancel{background:none;color:#4d6bfe;border:1px solid #4d6bfe;margin-top:12px}"
                        + ".cancel:hover{background:#f0f2ff}"
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
                        + URLEncoder.encode(errorPage, StandardCharsets.UTF_8).replace("+", "%20"));
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
                activity.progressBar.setProgress(0);
                activity.progressBar.setVisibility(View.VISIBLE);
                activity.urlBar.setText(url);
                activity.btnStop.setVisibility(View.VISIBLE);
                activity.btnRefresh.setVisibility(View.GONE);
            });
        }

        public void onPageStop(GeckoSession session, boolean success) {
            MainActivity activity = activityRef.get();
            if (activity == null || session != activity.getCurrentSession()) return;
            activity.runOnUiThread(() -> {
                activity.progressBar.setVisibility(View.GONE);
                activity.btnStop.setVisibility(View.GONE);
                activity.btnRefresh.setVisibility(View.VISIBLE);
            });
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
            return GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
        }

        public GeckoResult<Integer> onMediaPermissionRequest(
                GeckoSession session, String uri,
                GeckoSession.PermissionDelegate.MediaSource[] video,
                GeckoSession.PermissionDelegate.MediaSource[] audio) {
            MainActivity activity = activityRef.get();
            if (activity == null)
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            List<String> needed = new ArrayList<>();
            if (video != null && video.length > 0 &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (audio != null && audio.length > 0 &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            if (!needed.isEmpty()) {
                activity.requestPermissions(needed.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            }
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
        }

        public GeckoResult<Integer> onGeckoPermissionRequest(
                GeckoSession session, String uri,
                int type, GeckoSession.PermissionDelegate.Callback callback) {
            MainActivity activity = activityRef.get();
            if (activity == null)
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            if (type == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                } else {
                    activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            REQUEST_CODE_PERMISSIONS);
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

    private GeckoSession getCurrentSession() {
        if (currentTabIndex >= 0 && currentTabIndex < sessions.size()) {
            return sessions.get(currentTabIndex);
        }
        return null;
    }
}
