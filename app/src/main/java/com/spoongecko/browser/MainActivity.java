package com.spoongecko.browser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
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

        initializeViews();
        checkPermissions();
        startKeepAliveService();
        requestBatteryOptimizationExemption();

        setupGeckoView();

        // Default homepage
        String homepage = "https://duckduckgo.com";
        urlBar.setText(homepage);
        loadUrl(homepage);

        setupNavigationButtons();
    }

    private void initializeViews() {
        geckoView = findViewById(R.id.geckoView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setMax(100);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Handle POST_NOTIFICATIONS result if needed
            if (permissions.length > 0 && Manifest.permission.POST_NOTIFICATIONS.equals(permissions[0])) {
                boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                if (!granted) {
                    // optional: inform user notifications are disabled
                }
            }
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
                            } catch (ActivityNotFoundException ex) {
                                // Fallback: open app settings
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

    private void startKeepAliveService() {
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(serviceIntent);
            } catch (IllegalStateException e) {
                // Fallback to startService if startForegroundService is not allowed
                startService(serviceIntent);
            }
        } else {
            startService(serviceIntent);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupGeckoView() {
        // GeckoRuntimeSettings and runtime creation (GeckoView 153 compatible)
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .build();

        runtime = GeckoRuntime.create(this, settings);

        session = new GeckoSession();
        try {
            session.open(runtime);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open Gecko session", Toast.LENGTH_SHORT).show();
            session = null;
            return;
        }

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

            @Override
            public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {
                MainActivity.this.canGoBack = canGoBack;
                runOnUiThread(() -> btnBack.setEnabled(canGoBack));
            }

            @Override
            public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
                MainActivity.this.canGoForward = canGoForward;
                runOnUiThread(() -> btnForward.setEnabled(canGoForward));
            }
        });
    }

    private void setupNavigationButtons() {
        btnBack.setOnClickListener(v -> {
            if (canGoBack && session != null) {
                try {
                    session.goBack();
                } catch (Exception ignored) {}
            }
        });

        btnForward.setOnClickListener(v -> {
            if (canGoForward && session != null) {
                try {
                    session.goForward();
                } catch (Exception ignored) {}
            }
        });

        btnReload.setOnClickListener(v -> {
            if (session != null) {
                try {
                    session.reload();
                } catch (Exception ignored) {}
            }
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean handled = false;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String input = urlBar.getText() != null ? urlBar.getText().toString().trim() : "";
                if (!TextUtils.isEmpty(input)) {
                    loadUrl(input);
                    // hide keyboard
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
                }
                handled = true;
            }
            return handled;
        });
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

        try {
            session.loadUri(url);
            runOnUiThread(() -> urlBar.setText(url));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load URL", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // GeckoSession does not always require explicit resume; keep safe checks
        if (session == null && runtime != null) {
            session = new GeckoSession();
            try {
                session.open(runtime);
                geckoView.setSession(session);
            } catch (Exception ignored) {
                session = null;
            }
        }
    }

    @Override
    protected void onPause() {
        // Keep session open for faster resume; close only in onDestroy
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {}
            session = null;
        }
        if (runtime != null) {
            try {
                runtime.shutdown();
            } catch (Exception ignored) {}
            runtime = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (canGoBack && session != null) {
            try {
                session.goBack();
                return;
            } catch (Exception ignored) {}
        }
        super.onBackPressed();
    }
}
