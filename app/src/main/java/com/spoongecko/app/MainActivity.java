package com.spoongecko.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends AppCompatActivity {

    private GeckoView geckoView;
    private GeckoSession session;
    private GeckoRuntime runtime;
    private TextView heartbeat;
    private int tick = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            tick++;
            if (heartbeat != null) {
                heartbeat.setText("Alive: " + tick + "s");
            }
            SpoonGeckoApp.log("Heartbeat tick " + tick);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SpoonGeckoApp.log("MainActivity.onCreate: enter");

        // Set crash handler BEFORE anything else
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            SpoonGeckoApp.log("UNCAUGHT: " + throwable);
            java.io.StringWriter sw = new java.io.StringWriter();
            throwable.printStackTrace(new java.io.PrintWriter(sw));
            SpoonGeckoApp.log(sw.toString());
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        setContentView(R.layout.activity_main);
        SpoonGeckoApp.log("MainActivity: layout inflated");

        geckoView = findViewById(R.id.geckoView);
        heartbeat = findViewById(R.id.heartbeat);
        SpoonGeckoApp.log("MainActivity: views bound");

        // Start heartbeat
        handler.post(heartbeatTask);
        SpoonGeckoApp.log("MainActivity: heartbeat started");

        try {
            runtime = SpoonGeckoApp.getRuntime(this);
            SpoonGeckoApp.log("MainActivity: runtime obtained");
        } catch (Throwable t) {
            SpoonGeckoApp.log("MainActivity: runtime FAILED: " + t);
            return;
        }

        try {
            session = new GeckoSession();
            SpoonGeckoApp.log("MainActivity: session created");

            session.open(runtime);
            SpoonGeckoApp.log("MainActivity: session opened");

            geckoView.setSession(session);
            SpoonGeckoApp.log("MainActivity: geckoView.setSession done");

            // Load about:blank first to verify basic rendering works
            SpoonGeckoApp.log("MainActivity: skipping about:blank");

            // After 2 seconds, try loading the real home page
            handler.postDelayed(() -> {
                SpoonGeckoApp.log("MainActivity: now loading home.html");
                try {
                    session.loadUri("resource://android/assets/home.html");
                    SpoonGeckoApp.log("MainActivity: simple home.html load requested");
                } catch (Throwable t) {
                    SpoonGeckoApp.log("MainActivity: home.html load FAILED: " + t);
                }
            }, 2000);

        } catch (Throwable t) {
            SpoonGeckoApp.log("MainActivity: GeckoView setup FAILED: " + t);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            SpoonGeckoApp.log(sw.toString());
        }

        SpoonGeckoApp.log("MainActivity.onCreate: exit");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(heartbeatTask);
        SpoonGeckoApp.log("MainActivity.onDestroy");
        super.onDestroy();
    }
}
