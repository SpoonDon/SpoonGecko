package com.spoongecko.app;

import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private GeckoView geckoView;
    private EditText urlBar;
    private GeckoSession session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);

        // Initialize Engine
        GeckoRuntime runtime = GeckoRuntimeManager.getRuntime(this);

        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .useTrackingProtection(true)
                .suspendMediaWhenInactive(true)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .allowJavascript(true)
                .build();

        session = new GeckoSession(settings);
        session.open(runtime);

        // 1. Navigation Delegate (Handles URL updates & HTTPS->HTTP fallback)
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            
            // Exact GeckoView 153 signature
            @Override
            public void onLocationChange(@NonNull GeckoSession session, 
                                         @Nullable String url, 
                                         @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms, 
                                         boolean hasUserGesture) {
                // Update URL bar only if the user isn't currently typing in it
                if (url != null && !urlBar.hasFocus()) {
                    urlBar.setText(url);
                }
            }

            @Override
            public GeckoResult<String> onLoadError(@NonNull GeckoSession session, 
                                                   @Nullable String uri, 
                                                   @NonNull WebRequestError error) {
                // FIX 1: Fallback to HTTP if HTTPS fails on a local host
                if (uri != null && uri.startsWith("https://")) {
                    String host = uri.substring(8).split("/")[0];
                    if (UrlNormalizer.isLocalHost(host)) {
                        String httpUrl = "http://" + uri.substring(8);
                        session.loadUri(httpUrl);
                        return null; // Let the new URI load handle the UI
                    }
                }

                // FIX 2: Prevent the "White Tab" for all other network errors
                String errorHtml = "<html><body style='background:#121212; color:#ffffff; font-family:sans-serif; text-align:center; padding:20px;'>" +
                        "<h1>Unable to load page</h1>" +
                        "<p>" + (uri != null ? uri : "Unknown URL") + "</p>" +
                        "<p style='color:#aaaaaa;'>Error code: " + error.code + "</p>" +
                        "</body></html>";
                
                return GeckoResult.fromValue(errorHtml);
            }
        });

        // 2. Progress Delegate (Updates URL bar on initial page start)
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
                if (!urlBar.hasFocus()) {
                    urlBar.setText(url);
                }
            }
        });

        geckoView.setSession(session);

        // 3. URL Bar Input Handling
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                String query = v.getText().toString().trim();
                if (!query.isEmpty()) {
                    String url = UrlNormalizer.normalize(query);
                    if (!url.isEmpty()) {
                        session.loadUri(url);
                    }
                }
                
                urlBar.clearFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (session != null) session.setActive(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && session != null) {
            session.close();
        }
    }
}
