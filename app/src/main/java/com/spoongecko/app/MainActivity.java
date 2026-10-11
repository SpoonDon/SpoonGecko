package com.spoongecko.app;

import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SpoonGecko";

    private GeckoView geckoView;
    private GeckoSession session;
    private GeckoRuntime runtime;
    private EditText urlBar;
    private boolean canGoBack = false;
    private boolean canGoForward = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: enter");

        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: layout inflated");

        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnForward = findViewById(R.id.btnForward);
        ImageButton btnReload = findViewById(R.id.btnReload);
        Log.d(TAG, "onCreate: views bound");

        try {
            runtime = SpoonGeckoApp.getRuntime(this);
            Log.d(TAG, "onCreate: runtime obtained");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate: runtime failed", t);
            return;
        }

        try {
            BrowserKeepAliveService.start(this);
            Log.d(TAG, "onCreate: keep-alive started");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate: keep-alive failed", t);
        }

        createSession();

        btnBack.setOnClickListener(v -> { if (canGoBack) session.goBack(); });
        btnForward.setOnClickListener(v -> { if (canGoForward) session.goForward(); });
        btnReload.setOnClickListener(v -> session.reload());

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || enter) {
                navigate(urlBar.getText().toString());
                urlBar.clearFocus();
                return true;
            }
            return false;
        });

        Log.d(TAG, "onCreate: exit");
    }

    private void createSession() {
        Log.d(TAG, "createSession: enter");
        session = new GeckoSession();

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                Log.d(TAG, "onLocationChange: " + url);
                if (url != null && !urlBar.hasFocus()) {
                    urlBar.setText(url);
                }
            }
            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String url, WebRequestError error) {
                Log.e(TAG, "onLoadError: " + url + " err=" + error);
                return null;
            }
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                Log.d(TAG, "onNewSession: " + uri);
                GeckoSession newSession = new GeckoSession();
                newSession.setNavigationDelegate(this);
                geckoView.setSession(newSession);
                return GeckoResult.fromValue(newSession);
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                Log.d(TAG, "onPageStart: " + url);
            }
            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                Log.d(TAG, "onPageStop: success=" + success);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession s) {
                Log.e(TAG, "ContentDelegate.onCrash: content process crashed");
            }
            @Override
            public void onFirstComposite(GeckoSession s) {
                Log.d(TAG, "ContentDelegate.onFirstComposite: first frame drawn");
            }
        });

        session.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
            @Override
            public void onHistoryStateChange(GeckoSession s,
                                             GeckoSession.HistoryDelegate.HistoryList historyList) {
                int current = historyList.getCurrentIndex();
                canGoBack = current > 0;
                canGoForward = current < historyList.size() - 1;
            }
        });

        Log.d(TAG, "createSession: opening session");
        try {
            session.open(runtime);
            Log.d(TAG, "createSession: session opened");
        } catch (Throwable t) {
            Log.e(TAG, "createSession: session.open failed", t);
            return;
        }

        geckoView.setSession(session);
        Log.d(TAG, "createSession: geckoView.setSession done");

        // Clear stale asset cache
        try {
            File cacheDir = new File(getCacheDir(), "android_asset");
            if (cacheDir.exists()) {
                deleteRecursive(cacheDir);
                Log.d(TAG, "createSession: cache cleared");
            }
        } catch (Throwable t) {
            Log.e(TAG, "createSession: cache clear failed", t);
        }

        Log.d(TAG, "createSession: loading home.html");
        session.loadUri("resource://android/assets/home.html");
    }

    private void navigate(String input) {
        if (input == null || input.trim().isEmpty()) return;
        String url = input.trim();
        if (!url.startsWith("http") && !url.contains("://")) {
            if (url.contains(" ") || !url.contains(".")) {
                url = "https://duckduckgo.com/?q=" + url;
            } else {
                url = "https://" + url;
            }
        }
        Log.d(TAG, "navigate: " + url);
        session.loadUri(url);
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        if (session != null) session.setActive(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (geckoView != null) {
            try { geckoView.releaseSession(); } catch (Throwable ignored) {}
        }
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
