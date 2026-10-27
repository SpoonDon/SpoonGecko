package com.spoongecko.browser;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "https://lite.duckduckgo.com/lite/";
    private static final String STATE_KEY = "spoongecko_session_state";

    private static GeckoRuntime sRuntime;

    private GeckoSession session;
    private GeckoView geckoView;
    private GeckoSession.SessionState latestState;
    private boolean canGoBack = false;

    /**
     * Build (or reuse) the process-wide GeckoRuntime.
     *
     * Telemetry is left unconfigured: GeckoView only sends telemetry if a
     * telemetry delegate is explicitly supplied. Not calling telemetryDelegate()
     * therefore keeps telemetry off.
     *
     * Crash reporting is also not enabled: no crashHandler(Class) is provided,
     * so no crash Service is started.
     */
    private static synchronized GeckoRuntime ensureRuntime(final Activity activity) {
        if (sRuntime == null) {
            final GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    // Do NOT call telemetryDelegate(...) — keeps telemetry disabled.
                    // Do NOT call crashHandler(...) — keeps crash reporter disabled.
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
        if (restored != null) {
            session.restoreState(restored);
        } else {
            session.loadUri(DEFAULT_URL);
        }
    }

    private void attachDelegates(final GeckoSession s) {
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession sess,
                                             GeckoSession.SessionState sessionState) {
                latestState = sessionState;
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
    protected void onResume() {
        super.onResume();
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onPause() {
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
        if (latestState != null) outState.putParcelable(STATE_KEY, latestState);
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
