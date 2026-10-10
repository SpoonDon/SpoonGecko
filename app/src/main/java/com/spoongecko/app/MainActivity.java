package com.spoongecko.app;

import android.os.Bundle;
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

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private GeckoView geckoView;
    private GeckoSession session;
    private GeckoRuntime runtime;
    private EditText urlBar;
    private boolean canGoBack = false;
    private boolean canGoForward = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnForward = findViewById(R.id.btnForward);
        ImageButton btnReload = findViewById(R.id.btnReload);

        runtime = SpoonGeckoApp.getRuntime(this);
        BrowserKeepAliveService.start(this);

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
    }

    private void createSession() {
        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                if (url != null && !urlBar.hasFocus()) {
                    urlBar.setText(url);
                }
            }
            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String url, WebRequestError error) {
                return null;
            }
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                GeckoSession newSession = new GeckoSession();
                newSession.setNavigationDelegate(this);
                geckoView.setSession(newSession);
                return GeckoResult.fromValue(newSession);
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {}
            @Override
            public void onPageStop(GeckoSession s, boolean success) {}
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

        session.open(runtime);
        geckoView.setSession(session);
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
        session.loadUri(url);
    }
}
