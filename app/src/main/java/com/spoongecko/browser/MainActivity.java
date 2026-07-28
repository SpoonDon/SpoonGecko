package com.spoongecko.browser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int KEEPALIVE_JOB_ID = 42;

    private GeckoView geckoView;
    private GeckoSession session;
    private GeckoRuntime runtime;

    private EditText urlBar;
    private ProgressBar progressBar;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnReload;

    private boolean canGoBack = false;
    private boolean canGoForward = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        checkNotificationPermissionIfNeeded();
        startKeepAliveService();
        requestBatteryOptimizationExemption();
        schedulePersistedJob();
        setupGeckoRuntimeAndSession();

        String homepage = "https://duckduckgo.com";
        if (urlBar != null) urlBar.setText(homepage);
        loadUrl(homepage);

        setupNavigationButtons();
    }

    private void initializeViews() {
        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        if (progressBar != null) progressBar.setMax(100);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
    }

    private void checkNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (permissions.length > 0 && Manifest.permission.POST_NOTIFICATIONS.equals(permissions[0])) {
                boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                if (!granted) {
                    Toast.makeText(this, "Notifications disabled", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void startKeepAliveService() {
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(serviceIntent);
            } catch (IllegalStateException e) {
                startService(serviceIntent);
            }
        } else {
            startService(serviceIntent);
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle("Keep Alive")
                        .setMessage("Allow Spoon Gecko to run in the background for better RAM persistence?")
                        .setPositiveButton("Allow", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception ex) {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }
        }
    }

    /**
     * Schedule a persisted JobScheduler job as a fallback to help revive the keepalive service.
     * Guarded by API level checks and uses Context.JOB_SCHEDULER_SERVICE for compatibility.
     */
    private void schedulePersistedJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            ComponentName comp = new ComponentName(this, KeepAliveJobService.class);
            JobInfo.Builder builder = new JobInfo.Builder(KEEPALIVE_JOB_ID, comp)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
            try {
                js.schedule(builder.build());
            } catch (Exception ignored) {
                // Some OEMs restrict JobScheduler behavior; ignore failures gracefully.
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupGeckoRuntimeAndSession() {
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .build();

        runtime = GeckoRuntime.create(this, settings);
        session = new GeckoSession();

        try {
            session.open(runtime);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to start Gecko session", Toast.LENGTH_LONG).show();
            session = null;
            return;
        }

        if (geckoView != null) geckoView.setSession(session);

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            public void onPageStart(final GeckoSession s, final String url) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (urlBar != null) urlBar.setText(url);
                        if (progressBar != null) progressBar.setVisibility(android.view.View.VISIBLE);
                    }
                });
            }

            public void onPageStop(final GeckoSession s, final boolean success) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressBar != null) progressBar.setVisibility(android.view.View.GONE);
                    }
                });
            }

            public void onProgressChange(final GeckoSession s, final int progress) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressBar != null) progressBar.setProgress(progress);
                    }
                });
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            public void onLocationChange(final GeckoSession s, final String url) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (urlBar != null) urlBar.setText(url);
                    }
                });
            }

            public void onCanGoBack(final GeckoSession s, final boolean canGoBack) {
                MainActivity.this.canGoBack = canGoBack;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (btnBack != null) btnBack.setEnabled(canGoBack);
                    }
                });
            }

            public void onCanGoForward(final GeckoSession s, final boolean canGoForward) {
                MainActivity.this.canGoForward = canGoForward;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (btnForward != null) btnForward.setEnabled(canGoForward);
                    }
                });
            }

            // FIX APPLIED: Allows the browser to actually load clicked links
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session, @NonNull LoadRequest request) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
        });
    }

    private void setupNavigationButtons() {
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (canGoBack && session != null) {
                    try { session.goBack(); } catch (Exception ignored) {}
                }
            });
        }

        if (btnForward != null) {
            btnForward.setOnClickListener(v -> {
                if (canGoForward && session != null) {
                    try { session.goForward(); } catch (Exception ignored) {}
                }
            });
        }

        if (btnReload != null) {
            btnReload.setOnClickListener(v -> {
                if (session != null) {
                    try { session.reload(); } catch (Exception ignored) {}
                }
            });
        }

        if (urlBar != null) {
            urlBar.setOnEditorActionListener((v, actionId, event) -> {
                boolean handled = false;
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    String input = urlBar.getText() != null ? urlBar.getText().toString().trim() : "";
                    if (!TextUtils.isEmpty(input)) {
                        loadUrl(input);
                        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
                    }
                    handled = true;
                }
                return handled;
            });
        }
    }

    private void loadUrl(String url) {
        if (session == null) return;
        if (TextUtils.isEmpty(url)) return;

        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(" ")) {
                url = "https://duckduckgo.com/?q=" + Uri.encode(url);
            } else {
                url = "https://" + url;
            }
        }

        final String finalUrl = url;
        try {
            session.loadUri(finalUrl);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (urlBar != null) urlBar.setText(finalUrl);
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load URL", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ((session == null || !session.isOpen()) && runtime != null) {
            session = new GeckoSession();
            try {
                session.open(runtime);
                if (geckoView != null) geckoView.setSession(session);
            } catch (Exception ignored) {
                session = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            try { if (session.isOpen()) session.close(); } catch (Exception ignored) {}
            session = null;
        }
        // FIX APPLIED: REMOVED runtime.shutdown() to prevent crashes on screen rotation!
    }

    @Override
    public void onBackPressed() {
        if (canGoBack && session != null) {
            try { session.goBack(); return; } catch (Exception ignored) {}
        }
        super.onBackPressed();
    }
}
