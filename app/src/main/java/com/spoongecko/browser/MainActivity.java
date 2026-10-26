package com.spoongecko.browser;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity {

    private static final String TAG = "SpoonGecko";
    private static final String DEFAULT_URL = "https://lite.duckduckgo.com/lite/";
    private static final String EXTENSION_LOCATION = "resource://android/assets/messaging/";
    private static final String EXTENSION_ID = "messaging@example.com";

    private GeckoSession session;
    private GeckoView geckoView;
    private GeckoRuntime runtime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geckoView = findViewById(R.id.geckoview);

        // 1. Create runtime (shared engine)
        runtime = GeckoRuntime.create(this);

        // 2. Create session
        session = new GeckoSession();
        session.open(runtime);

        // 3. Attach session to the view
        geckoView.setSession(session);

        // 4. Restore state or load default page
        if (savedInstanceState != null) {
            session.restoreState(savedInstanceState);
        } else {
            session.loadUri(DEFAULT_URL);
        }

        // 5. Register a built-in extension (optional)
        runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                .accept(extension -> Log.i(TAG, "Extension ready: " + extension.id),
                        e -> Log.w(TAG, "Extension not installed (this is fine if no assets)"));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (session != null) {
            session.saveState(outState);
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
