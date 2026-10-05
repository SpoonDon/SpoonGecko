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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.List;

/**
 * SpoonGecko main browser screen.
 *
 * Single GeckoSession rendered in a single GeckoView, wrapped in a minimal
 * toolbar (back / forward / URL / reload). No tabs yet — deliberate for v1.
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

        // Correct order for GeckoView 157:
        //   1. open the session on the runtime
        //   2. attach it to the GeckoView
        //   3. load a URI
        session.open(runtime);
        wireSessionCallbacks();

        geckoView.setSession(session);
        session.loadUri(HOME_URI);

        wireToolbar();
    }

    private void wireSessionCallbacks() {

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession s, @NonNull String url) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
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

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(
                    @NonNull GeckoSession s,
                    @Nullable String url,
                    @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms) {
                currentUrl = (url == null) ? "" : url;
                updateUrlBar();
                updateNavigationButtons();
            }

            @Override
            public void onCanGoBack(@NonNull GeckoSession s, boolean canGoBack) {
                btnBack.setEnabled(canGoBack);
                btnBack.setAlpha(canGoBack ? 1f : 0.35f);
            }

            @Override
            public void onCanGoForward(@NonNull GeckoSession s, boolean canGoForward) {
                btnForward.setEnabled(canGoForward);
                btnForward.setAlpha(canGoForward ? 1f : 0.35f);
            }
        });
    }

    private void wireToolbar() {
        btnBack.setOnClickListener(v -> {
            if (session.canGoBack()) session.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (session.canGoForward()) session.goForward();
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
        boolean canBack = session.canGoBack();
        btnBack.setEnabled(canBack);
        btnBack.setAlpha(canBack ? 1f : 0.35f);

        boolean canFwd = session.canGoForward();
        btnForward.setEnabled(canFwd);
        btnForward.setAlpha(canFwd ? 1f : 0.35f);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && session != null && session.canGoBack()) {
            session.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (geckoView != null) {
            geckoView.setSession(null);
        }
        super.onDestroy();
    }
}
