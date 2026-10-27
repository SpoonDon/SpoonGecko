package com.spoongecko.browser;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity implements ComponentCallbacks2 {

    private static final String TAG = "SpoonGecko";
    private static final String DEFAULT_URL = "https://lite.duckduckgo.com/lite/";
    private static final String STATE_KEY = "spoongecko_session_state";

    // Process-wide runtime, survives Activity recreation (rotation, RAM kill).
    private static GeckoRuntime sRuntime;
    
    // A static fallback to persist session state if the OS kills the Activity 
    // in the background but keeps the process alive.
    private static GeckoSession.SessionState sPersistedState;

    private GeckoSession session;
    private GeckoView geckoView;
    private GeckoSession.SessionState latestState;
    private boolean canGoBack = false;

    private static synchronized GeckoRuntime ensureRuntime(final Activity activity) {
        if (sRuntime == null) {
            // Under-the-hood optimizations for the Gecko engine
            final GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    // 1. Content Blocking (Strict ETP)
                    .contentBlocking(new ContentBlocking.Settings.Builder()        
                                     .setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)        
                                     .build())
                    // 2. DNS-over-HTTPS (DoH) Fallback mode (Mode 2)
                    .trrMode(2)
                    // 3. Security: Enable Fission (site isolation)
                    .fissionEnabled(true)
                    // 4. Security: Disable remote debugging in production
                    .remoteDebuggingEnabled(false)
                    // 5. Telemetry is implicitly disabled because we don't provide a telemetryDelegate()
                    .build();

            sRuntime = GeckoRuntime.create(activity.getApplicationContext(), settings);
        }
        return sRuntime;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);
        sRuntime = ensureRuntime(this);

        session = new GeckoSession();
        attachDelegates(session);
        session.open(sRuntime);
        geckoView.setSession(session);

        GeckoSession.SessionState restored = null;
        if (savedInstanceState != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                restored = savedInstanceState.getParcelable(STATE_KEY, GeckoSession.SessionState.class);
            } else {
                final Parcelable p = savedInstanceState.getParcelable(STATE_KEY);
                if (p instanceof GeckoSession.SessionState) {
                    restored = (GeckoSession.SessionState) p;
                }
            }
        }
        
        // Fallback to the statically persisted state if the bundle is null
        if (restored == null) {
            restored = sPersistedState;
        }

        if (restored != null) {
            session.restoreState(restored);
        } else {
            session.loadUri(DEFAULT_URL);
        }
    }

    private void attachDelegates(final GeckoSession s) {
        // 6. Session State Tracking
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession sess, GeckoSession.SessionState sessionState) {
                latestState = sessionState;
            }
        });

        // 7. Content Blocking Tracker Logging (Optional: remove if you don't want log spam)
        s.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession session, ContentBlocking.BlockEvent event) {
                Log.d(TAG, "Blocked tracker: " + event.uri);
            }
        });

        // 8. Navigation & Pop-up handling
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean value) {
                MainActivity.this.canGoBack = value;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
                // Handles target="_blank" links by opening them in the same window
                return GeckoResult.fromValue(source);
            }
        });
        
        // Note: GeckoView defaults to DENYING camera/mic/location if no PermissionDelegate is set.
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d(TAG, "onTrimMemory: " + level);
        
        // Save the session state whenever the system signals memory pressure.
        if (latestState != null) {
            sPersistedState = latestState;
        }
        
        // GeckoView handles its own internal memory freeing. Do not call System.gc().
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "onLowMemory: System is running critically low on memory.");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // We handle orientation changes here because of android:configChanges in the Manifest,
        // preventing the Activity from restarting and the GeckoView from reloading.
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Lifecycle Optimization: Wakes up the engine, resumes JS execution
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onPause() {
        // Lifecycle Optimization: Suspends the engine, pauses JS timers, saves battery
        if (session != null) session.setActive(false);
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (session != null && canGoBack) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save to the bundle for normal activity restarts
        if (latestState != null) {
            outState.putParcelable(STATE_KEY, latestState);
        }
    }

    @Override
    protected void onDestroy() {
        // Memory cleanup
        if (geckoView != null) geckoView.setSession(null);
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
