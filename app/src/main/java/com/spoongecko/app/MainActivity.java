package com.spoongecko.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * SpoonGecko main browser screen.
 *
 * GeckoSession.open(runtime) is synchronous and returns void in GeckoView 157.
 * Session attach and initial navigation happen immediately after, in that
 * order. If the content process dies, ContentDelegate.onCrash fires and we
 * surface a Toast instead of silently white-screening.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SpoonGecko";
    private static final String HOME_URI = "resource://android/assets/home.html";
    private static final String SEARCH_URL = "https://duckduckgo.com/?q=";

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
        progressBar = findViewById(R.id.progressBar);

        try {
            runtime = SpoonGeckoApp.getRuntime(this);
        } catch (Throwable t) {
            Log.e(TAG, "Could not obtain GeckoRuntime", t);
            Toast.makeText(this,
                    "Browser engine failed to start. See logcat for details.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        createSession();
        wireToolbar();
        updateNavigationButtons();
    }

    private void createSession() {
        session = new GeckoSession();

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(@NonNull GeckoSession s) {
                Log.e(TAG, "Gecko content process crashed");
                sessionReady = false;
                runOnUiThread(() -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    Toast.makeText(MainActivity.this,
                            "Web content process crashed. Reopen the app.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });

        wireSessionCallbacks();

        try {
            session.open(runtime);
            sessionReady = true;
            Log.i(TAG, "GeckoSession opened");
        } catch (Throwable t) {
            Log.e(TAG, "GeckoSession failed to open", t);
            Toast.makeText(this,
                    "Could not open web session. See logcat for details.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        geckoView.setSession(session);
        session.loadUri(HOME_URI);
    }

    // ---------------------------------------------------------------------
    // GeckoSession callbacks
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Toolbar
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

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
        return s.matches("^[\\w-]+(\\.[\\w-]+)+(/.*)?$");
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Hardware back
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        if (sessionReady && session != null) {
            session.setActive(true);
        }
    }

    @Override
    protected void onPause() {
        if (sessionReady && session != null) {
            session.setActive(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sessionReady = false;

        if (geckoView != null) {
            try {
                geckoView.releaseSession();
            } catch (Throwable t) {
                Log.w(TAG, "releaseSession failed", t);
            }
        }

        if (session != null) {
            try {
                session.close();
            } catch (Throwable t) {
                Log.w(TAG, "session.close failed", t);
            }
            session = null;
        }

        geckoView = null;
        super.onDestroy();
    }
}
