package com.spoongecko.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private GeckoView geckoView;
    private GeckoSession geckoSession;
    private GeckoRuntime geckoRuntime;

    private EditText urlBar;
    private Button goButton;
    private ProgressBar progressBar;

    // Navigation state flags (replaces removed canGoBack/canGoForward)
    private boolean canGoBack = false;
    private boolean canGoForward = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI views
        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);
        goButton = findViewById(R.id.btn_go);
        progressBar = findViewById(R.id.progress_bar);

        // Setup GeckoRuntime (no deprecated builder methods!)
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false) // instead of setAboutConfigEnabled
                .build();

        geckoRuntime = GeckoRuntime.create(this, settings);

        // Create GeckoSession
        geckoSession = new GeckoSession();
        geckoSession.open(geckoRuntime);

        // Set delegates
        geckoSession.setNavigationDelegate(new NavigationDelegate());
        geckoSession.setProgressDelegate(new ProgressDelegate());
        geckoSession.setPermissionDelegate(new PermissionDelegate());

        // Attach session to GeckoView
        geckoView.setSession(geckoSession);

        // Load a default page
        geckoSession.loadUri("https://www.mozilla.org");

        // Setup URL bar and Go button
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl();
                return true;
            }
            return false;
        });

        goButton.setOnClickListener(v -> loadUrl());

        // Handle back button (using navigation flags)
        // The activity's onBackPressed will check canGoBack
    }

    private void loadUrl() {
        String input = urlBar.getText().toString().trim();
        if (input.isEmpty()) {
            return;
        }

        // Simple URL formatting: if no scheme, prepend https://
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            // Check if it's a search query or a domain
            if (input.contains(".")) {
                input = "https://" + input;
            } else {
                // Treat as search query using DuckDuckGo
                input = "https://duckduckgo.com/?q=" + input.replace(" ", "+");
            }
        }

        geckoSession.loadUri(input);
        urlBar.clearFocus();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) {
            geckoSession.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (geckoSession != null) {
            geckoSession.setActive(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (geckoSession != null) {
            geckoSession.setActive(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
        if (geckoRuntime != null) {
            geckoRuntime.shutdown();
            geckoRuntime = null;
        }
        super.onDestroy();
    }

    // ---------- Navigation Delegate ----------
    // Important: DO NOT use @Override annotations inside this anonymous class!
    private class NavigationDelegate implements GeckoSession.NavigationDelegate {

        // Called when navigation state changes for back/forward
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            MainActivity.this.canGoBack = canGoBack;
            // Optionally update UI (e.g., back button enabled/disabled)
        }

        public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            MainActivity.this.canGoForward = canGoForward;
        }

        public GeckoResult<AllowOrDeny> onLoadRequest(
                GeckoSession session,
                LoadRequest request
        ) {
            // Allow all navigation requests by default
            return GeckoResult.allow();
        }

        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(
                GeckoSession session,
                LoadRequest request
        ) {
            // Allow subframe loads
            return GeckoResult.allow();
        }

        public GeckoResult<GeckoSession> onNewSession(
                GeckoSession session,
                String uri
        ) {
            // For simplicity, open new windows in same session (or return null to ignore)
            // You could implement tab support here.
            return GeckoResult.fromValue(null);
        }

        public GeckoResult<AllowOrDeny> onLoadError(
                GeckoSession session,
                String uri,
                WebRequestError error
        ) {
            // Show error message to user
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                            "Error loading page: " + error.getMessage(),
                            Toast.LENGTH_LONG).show()
            );
            return GeckoResult.deny();
        }
    }

    // ---------- Progress Delegate ----------
    private class ProgressDelegate implements GeckoSession.ProgressDelegate {

        public void onPageStart(GeckoSession session, String url) {
            runOnUiThread(() -> {
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
            });
        }

        public void onPageStop(GeckoSession session, boolean success) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
            });
        }

        public void onProgressChange(GeckoSession session, int progress) {
            runOnUiThread(() -> {
                progressBar.setProgress(progress);
            });
        }

        // The following methods are optional; we'll keep them empty.
        public void onSecurityChange(GeckoSession session, SecurityInformation securityInfo) {}
        public void onSessionStateChange(GeckoSession session, SessionState sessionState) {}
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            // This is already handled by NavigationDelegate.
            // We'll ignore it here to avoid duplication.
        }
        public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            // This is already handled by NavigationDelegate.
        }
    }

    // ---------- Permission Delegate ----------
    private class PermissionDelegate implements GeckoSession.PermissionDelegate {

        private static final int REQUEST_CODE_PERMISSIONS = 1;

        public GeckoResult<Integer> onContentPermissionRequest(
                GeckoSession session,
                ContentPermission perm
        ) {
            // For content permissions (e.g., tracking protection), we can grant by default
            // or prompt the user. For now, grant all content permissions.
            return GeckoResult.fromValue(PermissionDelegate.CONTENT_PERMISSION_ALLOW);
        }

        public GeckoResult<Integer> onMediaPermissionRequest(
                GeckoSession session,
                String uri,
                MediaSource[] video,
                MediaSource[] audio
        ) {
            // Request Android runtime permissions if not granted
            List<String> neededPermissions = new ArrayList<>();
            if (video != null && video.length > 0 &&
                    ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.CAMERA);
            }
            if (audio != null && audio.length > 0 &&
                    ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.RECORD_AUDIO);
            }

            if (!neededPermissions.isEmpty()) {
                // Request permissions asynchronously
                String[] permissionsArray = neededPermissions.toArray(new String[0]);
                ActivityCompat.requestPermissions(MainActivity.this,
                        permissionsArray, REQUEST_CODE_PERMISSIONS);
                // For simplicity, we'll deny media permission until user grants them.
                // In a real app, you'd need to handle the permission result and then respond.
                // But GeckoView will re-request the permission if needed.
                return GeckoResult.fromValue(PermissionDelegate.PERMISSION_DENY);
            }

            // Grant access to both video and audio if available
            return GeckoResult.fromValue(PermissionDelegate.PERMISSION_ALLOW);
        }

        public GeckoResult<Integer> onGeckoPermissionRequest(
                GeckoSession session,
                String uri,
                int type,
                Callback callback
        ) {
            // Handle geolocation, etc.
            if (type == PermissionDelegate.PERMISSION_GEOLOCATION) {
                // Check location permission
                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    return GeckoResult.fromValue(PermissionDelegate.PERMISSION_ALLOW);
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            REQUEST_CODE_PERMISSIONS);
                    return GeckoResult.fromValue(PermissionDelegate.PERMISSION_DENY);
                }
            }
            // For other permissions, deny by default
            return GeckoResult.fromValue(PermissionDelegate.PERMISSION_DENY);
        }

        public void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
            // This is called when the user responds to runtime permission requests.
            // We don't need to do anything here because GeckoView will re-request if needed.
        }
    }
}
