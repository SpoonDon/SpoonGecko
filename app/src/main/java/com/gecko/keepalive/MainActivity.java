package com.gecko.keepalive;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends AppCompatActivity {

    private GeckoView geckoView;
    private GeckoSession session;
    private GeckoRuntime runtime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. Start the Keep-Alive Foreground Service immediately
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // 2. Request to ignore battery optimizations (Crucial for OEMs)
        requestBatteryOptimizationExemption();

        // 3. Build the UI programmatically (to avoid needing XML layout files)
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("Enter URL (e.g., https://google.com)");
        urlInput.setText("https://www.google.com");
        
        Button goButton = new Button(this);
        goButton.setText("Go");

        geckoView = new GeckoView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT);
        
        layout.addView(urlInput);
        layout.addView(goButton);
        layout.addView(geckoView, params);
        
        setContentView(layout);

        // 4. Initialize GeckoView
        initializeGeckoView();

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = urlInput.getText().toString();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                session.loadUri(url);
            }
        });
    }

    private void initializeGeckoView() {
        if (runtime == null) {
            GeckoRuntimeSettings.Builder settingsBuilder = new GeckoRuntimeSettings.Builder();
            settingsBuilder.javaScriptEnabled(true);
            settingsBuilder.remoteDebuggingEnabled(false);
            runtime = GeckoRuntime.create(this, settingsBuilder.build());
        }

        if (session == null) {
            session = new GeckoSession();
            session.open(runtime);
            geckoView.setSession(session);
        }
        
        // Load default URL
        session.loadUri("https://www.google.com");
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
