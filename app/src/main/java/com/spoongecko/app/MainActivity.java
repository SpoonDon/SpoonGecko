package com.spoongecko.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * SpoonGecko main browser screen.
 *
 * Single GeckoSession rendered in a single GeckoView, wrapped in a minimal
 * toolbar (back / forward / URL / reload). No tabs yet — deliberate for v1.
 *
 * Session lifecycle:
 *   - session.setActive(true) in onResume keeps the Gecko compositor
 *     producing frames. Without this, the SurfaceView can be reclaimed
 *     when the device considers the app idle (very aggressive on MIUI/
 *     HyperOS), leaving a pure-white page behind.
 *   - session.setActive(false) in onPause releases resources cleanly.
 *
 * Back handling:
 *   - dispatchKeyEvent runs BEFORE the view tree, so we get the back key
 *     before GeckoView swallows it to forward to web content.
 */
public class MainActivity extends AppCompatActivity {

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

        runtime = GeckoRuntimeHolder.get(this);

        session = new GeckoSession();

        session.open(runtime);
        wireSessionCallbacks();

        geckoView.setSession(session);
        session.loadUri(HOME_URI);

        wireToolbar();
        updateNavigationButtons();
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
            if (canGoBack) session.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (canGoForward) session.goForward();
        });
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
        if (currentUrl == null
                || currentUrl.isEmpty()
                || currentUrl.startsWith("resource://")) {
            if (!urlBar.hasFocus()) {
                urlBar.setText("");
            }
        } else {
            if (!urlBar.hasFocus()) {
                urlBar.setText(currentUrl);
            }
        }
    }

    private void updateNavigationButtons() {
        btnBack.setEnabled(canGoBack);
        btnBack.setAlpha(canGoBack ? 1f : 0.35f);

        btnForward.setEnabled(canGoForward);
        btnForward.setAlpha(canGoForward ? 1f : 0.35f);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------------
    // Hardware back — dispatched before the GeckoView view tree
    // ---------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP
                && session != null
                && canGoBack) {
            session.goBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------------
    // Lifecycle — keep the Gecko compositor alive while foreground
    // ---------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            session.setActive(true);
        }
    }

    @Override
    protected void onPause() {
        if (session != null) {
            session.setActive(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (geckoView != null) {
            geckoView.setSession(null);
        }
        super.onDestroy();
    }
}
