package com.spoongecko.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.List;

public final class ExtensionSessionManager {

    public interface SessionProvider {
        List<GeckoSession> getSessions();
    }

    private static final String TAG = "ExtSessionManager";
    private static final ExtensionSessionManager INSTANCE = new ExtensionSessionManager();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final List<WebExtension> extensions = new ArrayList<>();
    private final ExtensionActionManager actionManager = ExtensionActionManager.getInstance();
    private Context appContext;
    private SessionProvider sessionProvider;
    private ExtensionSessionTabDelegate tabDelegate;

    public static ExtensionSessionManager getInstance() {
        return INSTANCE;
    }

    private ExtensionSessionManager() {}

    public void init(Context context, SessionProvider provider) {
        appContext = context != null ? context.getApplicationContext() : null;
        sessionProvider = provider;
    }

    public void setTabOpener(Runnable opener) {
        tabDelegate = new ExtensionSessionTabDelegate(opener);
    }

    public void refresh(GeckoRuntime runtime) {
        if (runtime == null || appContext == null || !BuildConfig.EXTENSIONS_ENABLED) return;
        ExtensionController.list(appContext, runtime, new ExtensionController.ListCallback() {
            @Override
            public void onResult(List<WebExtension> result) {
                runOnUiThread(() -> {
                    setExtensions(result);
                    syncAllInternal();
                });
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    public void setExtensions(List<WebExtension> newExtensions) {
        synchronized (extensions) {
            extensions.clear();
            if (newExtensions != null) {
                for (WebExtension extension : newExtensions) {
                    if (extension == null) continue;
                    extensions.add(extension);
                    actionManager.register(extension);
                }
            }
        }
    }

    public void sync(GeckoSession session) {
        if (session == null || !BuildConfig.EXTENSIONS_ENABLED) return;
        synchronized (extensions) {
            for (WebExtension extension : extensions) {
                if (extension == null) continue;
                try {
                    session.getWebExtensionController()
                            .setActionDelegate(extension, actionManager);
                    session.getWebExtensionController()
                            .setTabDelegate(extension, tabDelegate);
                } catch (Exception e) {
                    Log.e(TAG, "sync failed for " + extension.id, e);
                }
            }
        }
    }

    public void syncAll() {
        runOnUiThread(this::syncAllInternal);
    }

    private void syncAllInternal() {
        if (sessionProvider == null) return;
        for (GeckoSession session : sessionProvider.getSessions()) {
            sync(session);
        }
    }

    public void setTabActive(GeckoRuntime runtime, GeckoSession session, boolean active) {
        if (runtime == null || session == null || !BuildConfig.EXTENSIONS_ENABLED) return;
        try {
            runtime.getWebExtensionController().setTabActive(session, active);
        } catch (Exception e) {
            Log.e(TAG, "setTabActive failed", e);
        }
    }

    public void clear() {
        setExtensions(null);
    }

    private static void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN.post(runnable);
        }
    }
}
