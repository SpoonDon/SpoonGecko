package com.spoongecko.browser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;

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

        checkPermissions();
        startKeepAliveService();
        requestBatteryOptimizationExemption();

        initializeViews();
        setupGeckoView();

        urlBar.setText("https://duckduckgo.com");
        loadUrl("https://duckduckgo.com");

        setupNavigationButtons();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle("Keep Alive")
                        .setMessage("Allow Spoon Gecko to run in the background for better RAM persistence?")
                        .setPositiveButton("Allow", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }
        }
    }

    private void startKeepAliveService() {
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void initializeViews() {
        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupGeckoView() {
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .useHardwareAcceleration(true)
                .setAboutConfigEnabled(false)
                .build();

        runtime = GeckoRuntime.create(this, settings);

        session = new GeckoSession();
        session.open(runtime);

        geckoView.setSession(session);

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
                runOnUiThread(() -> {
                    urlBar.setText(url);
                    progressBar.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onPageStop(@NonNull GeckoSession session, boolean success) {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }

            @Override
            public void onProgressChange(@NonNull GeckoSession session, int progress) {
                runOnUiThread(() -> progressBar.setProgress(progress));
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(@NonNull GeckoSession session, @NonNull String url) {
                runOnUiThread(() -> urlBar.setText(url));
            }

            public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
                MainActivity.this.canGoBack = canGoBack;
                runOnUiThread(() -> btnBack.setEnabled(canGoBack));
            }

            public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
                MainActivity.this.canGoForward = canGoForward;
                runOnUiThread(() -> btnForward.setEnabled(canGoForward));
            }
        });
    }

    private void loadUrl(String url) {
        if (session != null) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            session.loadUri(url);
            urlBar.setText(url);
        }
    }

    private void setupNavigationButtons() {
        btnBack.setOnClickListener(v -> {
            if (canGoBack) {
                session.goBack();
            }
        });

        btnForward.setOnClickListener(v -> {
            if (canGoForward) {
                session.goForward();
            }
        });

        btnReload.setOnClickListener(v -> {
            if (session != null) {
                session.reload();
            }
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            loadUrl(urlBar.getText().toString());
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            session.open(runtime);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            session.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.close();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
