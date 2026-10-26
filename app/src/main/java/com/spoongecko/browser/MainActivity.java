package com.spoongecko.browser;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "https://lite.duckduckgo.com/lite/";
    private static final String STATE_KEY = "spoongecko_session_state";

    // Process-wide runtime, survives Activity recreation.
    private static GeckoRuntime sRuntime;

    private GeckoSession session;
    private GeckoView geckoView;
    private GeckoSession.SessionState latestState;

    // GeckoSession has no canGoBack(); we track it from NavigationDelegate.
    private boolean canGoBack = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);

        // 1. Shared runtime (application context — no Activity leak).
        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(getApplicationContext());
        }

        // 2. Create session and wire delegates.
        session = new GeckoSession();
        attachDelegates(session);

        // 3. Open session and attach to the view.
        session.open(sRuntime);
        geckoView.setSession(session);

        // 4. Restore state (API 33+ uses type-safe overload).
        GeckoSession.SessionState restored = null;
        if (savedInstanceState != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                restored = savedInstanceState.getParcelable(STATE_KEY, GeckoSession.SessionState.class);
            } else {
                Parcelable p = savedInstanceState.getParcelable(STATE_KEY);
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

            // 🔑 Track back/forward availability.
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                MainActivity.this.canGoBack = canGoBack;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String uri) {
                // Single-tab model: open target="_blank" in the same session.
                return GeckoResult.fromValue(source);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            session.setActive(true);
        }
    }

    @Override
    protected void onPause() {
        if (session != null) {
            session.setActive(false);
        }
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // GeckoSession does not expose canGoBack(); use the delegate flag.
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
        if (geckoView != null) {
            geckoView.setSession(null);
        }
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
