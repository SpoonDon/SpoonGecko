package com.spoongecko.app;

import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * Stage 1 activity: one GeckoView, one GeckoSession, load a page.
 *
 * Critical sequencing (landmine #4):
 *   1. session.open(runtime)          — async; binds runtime to session.
 *   2. geckoView.setSession(session)  — attaches session to the view.
 *   3. onReady() fires later.  Only THEN do we loadUri().
 *
 * Calling loadUri() immediately after open() can SIGSEGV libxul.so on cold start.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SpoonGecko";
    private static final String START_URL = "https://mozilla.org";

    private GeckoView geckoView;
    private GeckoSession session;
    private boolean firstLoadStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // View hierarchy is built in code — no layout XML for Stage 1.
        FrameLayout root = new FrameLayout(this);
        geckoView = new GeckoView(this);
        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        GeckoRuntime runtime = SpoonGeckoApp.getRuntime();
        if (runtime == null) {
            Log.e(TAG, "GeckoRuntime is null — SpoonGeckoApp failed to initialize");
            return;
        }

        session = new GeckoSession();

        // Set OpenDelegate BEFORE open() so onReady is guaranteed to be delivered.
        session.setOpenDelegate(new GeckoSession.OpenDelegate() {
            @Override
            public void onReady(@NonNull GeckoSession s) {
                Log.i(TAG, "GeckoSession onReady");
                if (!firstLoadStarted) {
                    firstLoadStarted = true;
                    s.loadUri(START_URL);
                }
            }

            @Override
            public void onShutdown(@NonNull GeckoSession s) {
                Log.i(TAG, "GeckoSession onShutdown");
            }
        });

        // Content delegate is here purely so we can see *something* in logcat.
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession s, @Nullable String title) {
                Log.i(TAG, "Title: " + title);
            }
        });

        // Bind runtime to session. Async — do not loadUri yet.
        session.open(runtime);

        // Attach to view. Single-arg setSession is correct for GeckoView 157;
        // the view derives its runtime from the already-opened session.
        geckoView.setSession(session);
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
