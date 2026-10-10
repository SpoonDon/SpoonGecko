package com.spoongecko.app;

import java.util.List;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URI = "resource://android/assets/home.html";
    private static final String SEARCH_URL = "https://duckduckgo.com/?q=";
    private static final int REQ_POST_NOTIFICATIONS = 100;

    private GeckoView geckoView;
    private EditText urlBar;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnReload;
    private ProgressBar progressBar;

    private GeckoSession session;
    private GeckoRuntime runtime;

    private String currentUrl = "";
    private boolean canGoBack = false;
    private boolean canGoForward = false;
    private boolean sessionReady = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupLog.i("MainActivity.onCreate enter");

        requestNotificationPermissionIfNeeded();

        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
        progressBar = findViewById(R.id.progressBar);

        applyEdgeToEdgeInsets(findViewById(R.id.toolbar));

        try {
            runtime = SpoonGeckoApp.getRuntime(this);
            StartupLog.i("Runtime obtained");
        } catch (Throwable t) {
            StartupLog.e("Could not obtain GeckoRuntime", t);
            return;
        }

        BrowserKeepAliveService.start(this);
        StartupLog.i("Requested keep-alive service");

        promptForBatteryOptimizationExemption();

        createSession();
        wireToolbar();
        updateNavigationButtons();
        StartupLog.i("MainActivity.onCreate exit");
    }

    private void applyEdgeToEdgeInsets(View toolbar) {
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), 0);
            return insets;
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS);
    }

    private void promptForBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;

            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            StartupLog.e("battery optimization prompt failed", t);
        }
    }

    private void createSession() {
        session = new GeckoSession();

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFirstComposite(@NonNull GeckoSession s) {
                StartupLog.i("onFirstComposite");
            }

            @Override
            public void onCrash(@NonNull GeckoSession s) {
                StartupLog.e("onCrash", null);
                sessionReady = false;
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(@NonNull GeckoSession s,
                             @Nullable String url,
                             @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                             boolean hasUserGesture) {    
                currentUrl = url == null ? "" : url;    
                updateUrlBar();
            }

            @Override
            public GeckoResult<String> onLoadError(@NonNull GeckoSession s,
                                       @Nullable String url,
                                       @NonNull WebRequestError error) {    
                StartupLog.e("onLoadError " + url + " err=" + error, null);    
                return null; // Return a custom error page URI if you want, otherwise null
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(
                    @NonNull GeckoSession s, @NonNull String uri) {
                s.loadUri(uri);
                return GeckoResult.fromValue(s);
            }
        });

        wireSessionCallbacks();

        try {
            session.open(runtime);
            sessionReady = true;
        } catch (Throwable t) {
            StartupLog.e("session.open(runtime) threw", t);
            return;
        }

        geckoView.setSession(session);
        session.loadUri(HOME_URI);
    }

    private void wireSessionCallbacks() {

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession s, @NonNull String url) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                currentUrl = url;
                updateUrlBar();
            }

            @Override
            public void onPageStop(@NonNull GeckoSession s, boolean success) {
                progressBar.setVisibility(View.GONE);
                updateNavigationButtons();
            }

            @Override
            public void onProgressChange(@NonNull GeckoSession s, int progress) {
                progressBar.setProgress(progress);
            }
        });

        session.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
            @Override
            public void onHistoryStateChange(
                    @NonNull GeckoSession s,
                    @NonNull GeckoSession.HistoryDelegate.HistoryList historyList) {
                int current = historyList.getCurrentIndex();
                int size = historyList.size();
                canGoBack = current > 0;
                canGoForward = current >= 0 && current < size - 1;
                updateNavigationButtons();
            }
        });
    }

    private void wireToolbar() {
        btnBack.setOnClickListener(v -> {
            if (sessionReady && canGoBack) session.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (sessionReady && canGoForward) session.goForward();
        });
        btnReload.setOnClickListener(v -> {
            if (sessionReady) session.reload();
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || enter) {
                navigate(urlBar.getText().toString());
                urlBar.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void navigate(String input) {
        if (!sessionReady) return;

        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) return;

        String url;
        if (looksLikeUrl(trimmed)) {
            url = hasScheme(trimmed) ? trimmed : ("https://" + trimmed);
        } else {
            url = SEARCH_URL + Uri.encode(trimmed);
        }

        session.loadUri(url);
        currentUrl = url;
        updateUrlBar();
    }

    private static boolean hasScheme(String s) {
        return s.startsWith("http://")
                || s.startsWith("https://")
                || s.startsWith("about:")
                || s.startsWith("resource:")
                || s.startsWith("file:")
                || s.startsWith("data:");
    }

    private static boolean looksLikeUrl(String s) {
        if (s.contains(" ")) return false;
        if (hasScheme(s)) return true;
        if (s.matches("^localhost(:\\d+)?(/.*)?$")) return true;
        if (s.matches("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$")) return true;
        return Patterns.WEB_URL.matcher(s).matches();
    }

    private void updateUrlBar() {
        if (urlBar == null || urlBar.hasFocus()) return;

        if (currentUrl == null
                || currentUrl.isEmpty()
                || currentUrl.startsWith("resource://")) {
            urlBar.setText("");
        } else {
            urlBar.setText(currentUrl);
        }
    }

    private void updateNavigationButtons() {
        if (btnBack == null || btnForward == null) return;

        btnBack.setEnabled(canGoBack);
        btnBack.setAlpha(canGoBack ? 1f : 0.35f);

        btnForward.setEnabled(canGoForward);
        btnForward.setAlpha(canGoForward ? 1f : 0.35f);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && urlBar != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {

            if (urlBar != null && urlBar.hasFocus()) {
                urlBar.clearFocus();
                hideKeyboard();
                return true;
            }

            if (sessionReady && canGoBack) {
                session.goBack();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StartupLog.i("MainActivity.onResume");
        if (sessionReady && session != null) {
            session.setActive(true);
        }
    }

    @Override
    protected void onPause() {
        StartupLog.i("MainActivity.onPause");
        if (sessionReady && session != null) {
            session.setActive(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        StartupLog.i("MainActivity.onDestroy");
        sessionReady = false;

        // Note: we deliberately do NOT stop the keep-alive service here.
        // It runs as long as the app process is alive so the browser
        // survives OEM kill attempts during background use.

        if (geckoView != null) {
            try {
                geckoView.releaseSession();
            } catch (Throwable t) {
                StartupLog.e("releaseSession failed", t);
            }
        }

        if (session != null) {
            try {
                session.close();
            } catch (Throwable t) {
                StartupLog.e("session.close failed", t);
            }
            session = null;
        }

        geckoView = null;
        super.onDestroy();
    }
}
