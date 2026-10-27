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

    // Under-the-hood optimization #3:
    // 30fps is more than enough for a browser shell. Halves the GPU work vs. 60fps
    // and noticeably reduces battery drain during scrolling and video playback.
    private static final int PREFERRED_FRAME_RATE = 30;

    private static GeckoRuntime sRuntime;
    private static GeckoSession.SessionState sPersistedState;

    private GeckoSession session;
    private GeckoView geckoView;
    private GeckoSession.SessionState latestState;
    private boolean canGoBack = false;

    private static synchronized GeckoRuntime ensureRuntime(final Activity activity) {
        if (sRuntime == null) {
            final GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    // Content Blocking (Strict ETP)
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                            .build())
                    // DNS-over-HTTPS fallback (Mode 2 = TRR first, native DNS fallback)
                    .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
                    // Security: Fission (site isolation)
                    .fissionEnabled(true)
                    // Security: no remote debug socket in production
                    .remoteDebuggingEnabled(false)
                    // Under-the-hood optimization: cap renderer frame rate
                    .preferredFrameRate(PREFERRED_FRAME_RATE)
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

        // Start with an explicit hint so the process is treated as foreground.
        session.setPriorityHint(GeckoSession.PRIORITY_HINT_ACTIVE);

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
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession sess, GeckoSession.SessionState sessionState) {
                latestState = sessionState;
            }
        });

        s.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession session, ContentBlocking.BlockEvent event) {
                Log.d(TAG, "Blocked tracker: " + event.uri);
            }
        });

        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean value) {
                MainActivity.this.canGoBack = value;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
                return GeckoResult.fromValue(source);
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d(TAG, "onTrimMemory: " + level);
        if (latestState != null) {
            sPersistedState = latestState;
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "onLowMemory: System is running critically low on memory.");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            session.setActive(true);
            // Foreground again — full priority.
            session.setPriorityHint(GeckoSession.PRIORITY_HINT_ACTIVE);
        }
    }

    @Override
    protected void onPause() {
        if (session != null) {
            session.setActive(false);
            // Under-the-hood optimization #4:
            // We're temporarily invisible (e.g., system dialog, settings screen).
            // Instead of letting the OS background the process and potentially
            // kill it (losing the tab), bump priority to HIGH so the active
            // session's process is kept alive but throttled.
            session.setPriorityHint(GeckoSession.PRIORITY_HINT_HIGH);
        }
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
        if (latestState != null) {
            outState.putParcelable(STATE_KEY, latestState);
        }
    }

    @Override
    protected void onDestroy() {
        if (geckoView != null) geckoView.setSession(null);
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
