package com.spoongecko.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private GeckoView geckoView;
    private GeckoSession geckoSession;
    private GeckoRuntime geckoRuntime;

    private EditText urlBar;
    private Button goButton;
    private ProgressBar progressBar;

    private boolean canGoBack = false;
    private boolean canGoForward = false;

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "browser_channel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.gecko_view);
        urlBar = findViewById(R.id.url_bar);
        goButton = findViewById(R.id.btn_go);
        progressBar = findViewById(R.id.progress_bar);

        createNotificationChannel();
        startForegroundService();

        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .build();

        geckoRuntime = GeckoRuntime.create(this, settings);
        geckoRuntime.getSettings().setTelemetryEnabled(false);

        geckoSession = new GeckoSession();
        geckoSession.open(geckoRuntime);

        geckoSession.setNavigationDelegate(new NavigationDelegate(this));
        geckoSession.setProgressDelegate(new ProgressDelegate(this));
        geckoSession.setPermissionDelegate(new PermissionDelegate(this));

        geckoView.setSession(geckoSession);
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
            if (input.contains(".")) {
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Browser Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the browser alive in background.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundService() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Spoon Gecko")
                .setContentText("Browser running in background")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private static class NavigationDelegate implements GeckoSession.NavigationDelegate {
        private final WeakReference<MainActivity> activityRef;

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

        public GeckoResult<GeckoSession.AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
            return GeckoResult.allow();
        }

        public GeckoResult<GeckoSession.AllowOrDeny> onSubframeLoadRequest(GeckoSession session, LoadRequest request) {
            return GeckoResult.allow();
        }

        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            return GeckoResult.fromValue(null);
        }

        public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, "Error: " + error.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
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

        public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState sessionState) {}

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
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.CONTENT_PERMISSION_ALLOW);
        }

        public GeckoResult<Integer> onMediaPermissionRequest(GeckoSession session, String uri,
                                                             MediaSource[] video, MediaSource[] audio) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_DENY);

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
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_DENY);
            }
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_ALLOW);
        }

        public GeckoResult<Integer> onGeckoPermissionRequest(GeckoSession session, String uri,
                                                             int type, Callback callback) {
            MainActivity activity = activityRef.get();
            if (activity == null) return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_DENY);

            if (type == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_ALLOW);
                } else {
                    activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            REQUEST_CODE_PERMISSIONS);
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_DENY);
                }
            }
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.PERMISSION_DENY);
        }

        public void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {}
    }
}
