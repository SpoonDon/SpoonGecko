package com.gecko.keepalive;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends AppCompatActivity {

    private GeckoView geckoView;
    private GeckoSession session;
    private GeckoRuntime runtime;
    private boolean canGoBack = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        requestBatteryOptimizationExemption();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("Search with Brave or enter URL");
        urlInput.setText("");
        urlInput.setSingleLine(true);

        Button goButton = new Button(this);
        goButton.setText("Go");

        geckoView = new GeckoView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f);

        layout.addView(urlInput);
        layout.addView(goButton);
        layout.addView(geckoView, params);

        setContentView(layout);

        initializeGeckoView();

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = urlInput.getText().toString().trim();
                if (!input.isEmpty() && session != null) {
                    if (input.startsWith("http://") || input.startsWith("https://")) {
                        session.loadUri(input);
                    } else if (input.contains(".") && !input.contains(" ")) {
                        session.loadUri("https://" + input);
                    } else {
                        session.loadUri("https://search.brave.com/search?q=" + Uri.encode(input));
                    }
                    urlInput.setText("");
                    urlInput.clearFocus();
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (canGoBack && session != null) {
            session.goBack();
        } else {
            showExitDialog();
        }
    }

    private void showExitDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Exit Spoon Gecko?")
            .setMessage("You are on the first page. Do you want to exit the app?")
            .setPositiveButton("Exit", (dialog, which) -> {
                Intent serviceIntent = new Intent(MainActivity.this, KeepAliveService.class);
                stopService(serviceIntent);
                finishAndRemoveTask();
            })
            .setNegativeButton("Stay", (dialog, which) -> {
                dialog.dismiss();
            })
            .setCancelable(false)
            .show();
    }

    private void initializeGeckoView() {
        if (runtime == null) {
            GeckoRuntimeSettings.Builder settingsBuilder = new GeckoRuntimeSettings.Builder();
            settingsBuilder.javaScriptEnabled(true);
            settingsBuilder.remoteDebuggingEnabled(false);
            settingsBuilder.webFontsEnabled(true);
            settingsBuilder.automaticFontSizeAdjustment(true);
            runtime = GeckoRuntime.create(this, settingsBuilder.build());
        }

        if (session == null) {
            session = new GeckoSession();
            session.open(runtime);
            geckoView.setSession(session);

            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                    MainActivity.this.canGoBack = canGoBack;
                }
            });
        }

        session.loadUri("https://search.brave.com");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        }
    }
}
