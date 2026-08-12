package com.spoongecko.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_SESSION_STATE = "sessionState";

    private GeckoView geckoView;
    private GeckoSession geckoSession;
    private GeckoRuntime geckoRuntime;

    private EditText urlBar;
    private Button goButton;
    private ProgressBar progressBar;

    private boolean canGoBack = false;
    private boolean canGoForward = false;

    private GeckoSession.SessionState currentSessionState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);
        goButton = findViewById(R.id.btn_go);
        progressBar = findViewById(R.id.progress_bar);

        startService(new Intent(this, BrowserService.class));

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
                .setLnaEnabled(false)
                .setLnaBlocking(false)
                .setLnaBlockTrackers(false)
                .build();

        geckoRuntime = GeckoRuntime.create(this, settings);
        geckoSession = new GeckoSession();
        geckoSession.open(geckoRuntime);

        geckoSession.setNavigationDelegate(new NavigationDelegate(this));
        geckoSession.setProgressDelegate(new ProgressDelegate(this));
        geckoSession.setPermissionDelegate(new PermissionDelegate(this));

        geckoView.setSession(geckoSession);

        if (savedInstanceState != null) {
            String stateStr = savedInstanceState.getString(KEY_SESSION_STATE);
            if (stateStr != null) {
                GeckoSession.SessionState state = GeckoSession.SessionState.fromString(stateStr);
                if (state != null) {
                    geckoSession.restoreState(state);
                    return;
                }
            }
        }
        geckoSession.loadUri("https://www.mozilla.org");

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl();
                return true;
            }
            return false;
        });

        goButton.setOnClickListener(v -> loadUrl());
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentSessionState != null) {
            outState.putString(KEY_SESSION_STATE, currentSessionState.toString());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (geckoSession != null) geckoSession.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (geckoSession != null) geckoSession.setActive(true);
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

    private static class NavigationDelegate implements GeckoSession.NavigationDelegate {
        private final WeakReference<MainActivity> activityRef;
        private final Set<String> handledHosts = new HashSet<>();

        NavigationDelegate(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            MainActivity activity = activityRef.get();
            if (activity != null) activity.canGoBack = canGoBack;
        }

        public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            MainActivity activity = activityRef.get();
            if (activity != null) activity.canGoForward = canGoForward;
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
                final String finalUri = uri;

                if (handledHosts.contains(finalHost)) {
                    // Already handled this host – inform the user
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, "Certificate error. " + finalHost + " cannot be loaded securely.", Toast.LENGTH_LONG).show()
                    );
                    return GeckoResult.fromValue(null);
                }

                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] proceed = {false};

                activity.runOnUiThread(() -> {
                    new AlertDialog.Builder(activity)
                            .setTitle("Security Warning")
                            .setMessage("The certificate for " + finalHost + " is not trusted.\n\n" +
                                    "Connecting to this site may expose your information.\n\n" +
                                    "Do you want to proceed anyway?")
                            .setPositiveButton("Proceed (unsafe)", (dialog, which) -> {
                                proceed[0] = true;
                                handledHosts.add(finalHost);
                                latch.countDown();
                            })
                            .setNegativeButton("Cancel", (dialog, which) -> {
                                proceed[0] = false;
                                handledHosts.add(finalHost);
                                latch.countDown();
                            })
                            .setOnCancelListener(dialog -> {
                                proceed[0] = false;
                                handledHosts.add(finalHost);
                                latch.countDown();
                            })
                            .show();
                });

                try {
                    latch.await();
                } catch (InterruptedException ignored) {
                    return GeckoResult.fromValue(null);
                }

                if (proceed[0]) {
                    if (finalUri != null && finalUri.startsWith("https://")) {
                        String httpUri = finalUri.replace("https://", "http://");
                        // Load over HTTP – GeckoView will show the crossed-out padlock automatically
                        session.loadUri(httpUri);
                        return GeckoResult.fromValue(null);
                    } else {
                        // If already HTTP, just reload
                        session.loadUri(finalUri);
                        return GeckoResult.fromValue(null);
                    }
                } else {
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, "Load cancelled by user.", Toast.LENGTH_SHORT).show()
                    );
                    return GeckoResult.fromValue(null);
                }
            }

            activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Error loading page: " + error.getMessage(), Toast.LENGTH_LONG).show()
            );
            return GeckoResult.fromValue(null);
        }
    }

    private static class ProgressDelegate implements GeckoSession.ProgressDelegate {
        private final WeakReference<MainActivity> activityRef;

        ProgressDelegate(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public void onPageStart(GeckoSession session, String url) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                activity.progressBar.setProgress(0);
                activity.progressBar.setVisibility(View.VISIBLE);
                activity.urlBar.setText(url);
            });
        }

        public void onPageStop(GeckoSession session, boolean success) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> activity.progressBar.setVisibility(View.GONE));
        }

        public void onProgressChange(GeckoSession session, int progress) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> activity.progressBar.setProgress(progress));
        }

        public void onSecurityChange(GeckoSession session, SecurityInformation securityInfo) {}

        public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState sessionState) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                activity.currentSessionState = sessionState;
            }
        }

        public void onCanGoBack(GeckoSession session, boolean canGoBack) {}

        public void onCanGoForward(GeckoSession session, boolean canGoForward) {}
    }

    private static class PermissionDelegate implements GeckoSession.PermissionDelegate {
        private final WeakReference<MainActivity> activityRef;
        private static final int REQUEST_CODE_PERMISSIONS = 1;

        PermissionDelegate(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession session, ContentPermission perm) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
        }

        public GeckoResult<Integer> onMediaPermissionRequest(GeckoSession session, String uri,
                                                             MediaSource[] video, MediaSource[] audio) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);

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

        public GeckoResult<Integer> onGeckoPermissionRequest(GeckoSession session, String uri,
                                                             int type, Callback callback) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);

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

        public void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {}
    }                
}
