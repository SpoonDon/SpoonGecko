package com.spoongecko.browser;

import android.app.Activity;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity {

    private static final String TAG = "SpoonGecko";
    private static final String DEFAULT_URL = "https://lite.duckduckgo.com/lite/";
    private static final String EXTENSION_LOCATION = "resource://android/assets/messaging/";
    private static final String EXTENSION_ID = "messaging@example.com";
    private static final String STATE_KEY = "spoongecko_session_state";

    private GeckoSession session;
    private GeckoView geckoView;
    private GeckoRuntime runtime;
    private GeckoSession.SessionState latestState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);

        // 1. Create runtime (shared engine)
        runtime = GeckoRuntime.create(this);

        // 2. Create session
        session = new GeckoSession();

        // 3. Attach a ProgressDelegate to capture the latest SessionState
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession session,
                                             GeckoSession.SessionState sessionState) {
                latestState = sessionState;
            }
        });

        // 4. Open session and attach to the view
        session.open(runtime);
        geckoView.setSession(session);

        // 5. Restore state or load default page
        if (savedInstanceState != null) {
            Parcelable saved = savedInstanceState.getParcelable(STATE_KEY);
            if (saved instanceof GeckoSession.SessionState) {
                session.restoreState((GeckoSession.SessionState) saved);
            } else {
                session.loadUri(DEFAULT_URL);
            }
        } else {
            session.loadUri(DEFAULT_URL);
        }

        // 6. Register a built‑in extension (optional)
        runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                .accept(extension -> Log.i(TAG, "Extension ready: " + extension.id),
                        e -> Log.w(TAG, "Extension not installed (this is fine if no assets)"));
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
        if (session != null) {
            session.close();
        }
        super.onDestroy();
    }
}
