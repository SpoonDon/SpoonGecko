package com.spoongecko.browser;

import android.os.Bundle;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridges the bundled "SpoonGecko Scroll Hook" WebExtension to per-tab
 * scroll state.
 *
 * The extension's content script sends { atTop: bool } native messages on
 * scroll events. GeckoView routes those messages to the session-scoped
 * WebExtensionController's MessageDelegate, so tab A's messages only touch
 * tab A's flag.
 *
 * All version-sensitive GeckoView surface is centralized here. If
 * setMessageDelegate or install changes shape in a future GeckoView, only
 * this file needs adjusting.
 */
public final class ScrollExtension {

    private static final String NATIVE_APP = "SpoonScroll";
    private static final String EXTENSION_URI = "resource://android/assets/scroll_hook/";

    private static volatile ScrollExtension INSTANCE;

    private volatile WebExtension extension;

    /** Session + Tab pair awaiting extension readiness. */
    private static final class Pending {
        final GeckoSession session;
        final Tab tab;
        Pending(GeckoSession s, Tab t) { session = s; tab = t; }
    }
    private final List<Pending> pending = new ArrayList<>();

    private ScrollExtension() {}

    /**
     * One-time registration per process. Caller passes the GeckoRuntime
     * obtained from GeckoApp. Safe to call from multiple threads; the
     * underlying install is idempotent for a given URI.
     */
    public static ScrollExtension getOrCreate(GeckoRuntime runtime) {
        ScrollExtension local = INSTANCE;
        if (local != null) return local;
        synchronized (ScrollExtension.class) {
            if (INSTANCE == null) {
                ScrollExtension se = new ScrollExtension();
                se.install(runtime);
                INSTANCE = se;
            }
            return INSTANCE;
        }
    }

    private void install(GeckoRuntime runtime) {
        try {
            WebExtensionController controller = runtime.getWebExtensionController();
            controller.install(EXTENSION_URI)
                    .accept(ext -> {
                        extension = ext;
                        flushPending();
                    }, err -> {
                        // Extension registration failed — leave atTop = true
                        // for every tab, which is the permissive fallback.
                        extension = null;
                    });
        } catch (Exception ignored) {
            extension = null;
        }
    }

    /**
     * Bind a freshly-created session to the extension so its messages route
     * to this tab. If the extension isn't ready yet, the binding is queued
     * and flushed on install completion.
     */
    public void bind(GeckoSession session, Tab tab) {
        if (session == null || tab == null) return;
        WebExtension ext = extension;
        if (ext == null) {
            synchronized (pending) {
                pending.add(new Pending(session, tab));
            }
            return;
        }
        attach(session, tab, ext);
    }

    /**
     * Session-scoped message delegation. GeckoView 157 exposes this on
     * GeckoSession.getWebExtensionController() — NOT on GeckoSession
     * directly (the earlier compile failure).
     *
     * The session-scoped controller returns null until the session has been
     * opened via session.open(runtime). TabManager opens the session before
     * calling bind(), so this should never be null in practice; the null
     * check is defensive.
     */
    private void attach(GeckoSession session, Tab tab, WebExtension ext) {
        try {
            WebExtensionController controller = session.getWebExtensionController();
            if (controller == null) return;
            controller.setMessageDelegate(ext, (nativeApp, message, sender) -> {
                Boolean atTop = extractAtTop(message);
                if (atTop != null) tab.atTop = atTop;
                return null;
            }, NATIVE_APP);
        } catch (Exception ignored) {
            // If delegation fails, the tab keeps atTop = true (permissive).
        }
    }

    private void flushPending() {
        WebExtension ext = extension;
        if (ext == null) return;
        List<Pending> toBind;
        synchronized (pending) {
            toBind = new ArrayList<>(pending);
            pending.clear();
        }
        for (Pending p : toBind) attach(p.session, p.tab, ext);
    }

    /** Message payload is { atTop: bool }. Try the common shapes. */
    private static Boolean extractAtTop(Object message) {
        if (message == null) return null;
        try {
            if (message instanceof Map) {
                Object v = ((Map<?, ?>) message).get("atTop");
                if (v instanceof Boolean) return (Boolean) v;
            }
            if (message instanceof Bundle) {
                Bundle b = (Bundle) message;
                if (b.containsKey("atTop")) return b.getBoolean("atTop");
            }
            // Last resort: string-sniff. Matches "atTop":true / "atTop": false.
            String s = message.toString();
            int idx = s.indexOf("atTop");
            if (idx >= 0) {
                String tail = s.substring(idx);
                if (tail.contains("false")) return Boolean.FALSE;
                if (tail.contains("true")) return Boolean.TRUE;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
