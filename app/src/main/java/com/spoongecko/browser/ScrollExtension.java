package com.spoongecko.browser;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.Map;

/**
 * Bridges the bundled "SpoonGecko Scroll Hook" WebExtension to per-tab
 * scroll state.
 *
 * GeckoView 157 removed MessageSender.getSession() — the sender object no
 * longer carries a back-reference to the originating GeckoSession. Because
 * our single shared GeckoView only renders the active tab, and the content
 * script only reports on user interaction (which can't happen on a
 * background tab), we track the *current* tab's atTop flag instead of
 * looking up the session that sent the message.
 *
 * TabManager hands us a TabProvider on construction so we can resolve
 * "the current tab" at message time without holding a hard reference back
 * to the manager.
 */
public final class ScrollExtension {

    private static final String NATIVE_APP = "SpoonScroll";
    private static final String EXTENSION_URI = "resource://android/assets/scroll_hook/";

    private static volatile ScrollExtension INSTANCE;

    /**
     * Supplies the currently-visible tab. TabManager implements this by
     * returning getCurrentTab(). Called on the WebExtension message thread,
     * not the UI thread.
     */
    public interface TabProvider {
        Tab getCurrentTab();
    }

    private volatile WebExtension extension;
    private volatile TabProvider tabProvider;

    private ScrollExtension() {}

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

    /** Called by TabManager once in its constructor. */
    public void setTabProvider(TabProvider provider) {
        this.tabProvider = provider;
    }

    private void install(GeckoRuntime runtime) {
        try {
            WebExtensionController controller = runtime.getWebExtensionController();
            controller.install(EXTENSION_URI).accept(
                    ext -> {
                        extension = ext;
                        attachDelegate(ext);
                    },
                    err -> extension = null);
        } catch (Exception ignored) {
            extension = null;
        }
    }

    /**
     * One-time delegate registration. GeckoView 157 signature:
     *   GeckoResult<Object> onMessage(String nativeApp,
     *                                 Object message,
     *                                 MessageSender sender)
     */
    private void attachDelegate(WebExtension ext) {
        try {
            ext.setMessageDelegate(new WebExtension.MessageDelegate() {
                @Override
                public GeckoResult<Object> onMessage(String nativeApp,
                                                     Object message,
                                                     WebExtension.MessageSender sender) {
                    Boolean atTop = extractAtTop(message);
                    if (atTop != null) {
                        TabProvider p = tabProvider;
                        if (p != null) {
                            Tab t = p.getCurrentTab();
                            if (t != null) t.atTop = atTop;
                        }
                    }
                    return null;
                }
            }, NATIVE_APP);
        } catch (Exception ignored) {
            // If registration fails, tabs stay at atTop = true (permissive).
        }
    }

    /**
     * Retained for source-compatibility with TabManager. This class no
     * longer tracks sessions by reference, so these are no-ops.
     */
    public void bind(GeckoSession session, Tab tab) {}
    public void unbind(GeckoSession session) {}

    /**
     * Message payload is { atTop: bool }. GeckoView hands us the deserialised
     * object as one of: Boolean, Map, Bundle, or a JSON-ish string, depending
     * on build. Try the fast cases first, then fall back to a string scan that
     * reads the token immediately after "atTop:" so it can't be fooled by
     * other boolean fields in the same payload.
     */
    private static Boolean extractAtTop(Object message) {
        if (message == null) return null;
        if (message instanceof Boolean) return (Boolean) message;
        try {
            if (message instanceof Map) {
                Object v = ((Map<?, ?>) message).get("atTop");
                if (v instanceof Boolean) return (Boolean) v;
            }
            if (message instanceof android.os.Bundle) {
                android.os.Bundle b = (android.os.Bundle) message;
                if (b.containsKey("atTop")) return b.getBoolean("atTop");
            }
            String s = message.toString();
            int idx = s.indexOf("atTop");
            if (idx >= 0) {
                String tail = s.substring(idx);
                int colon = tail.indexOf(':');
                if (colon >= 0) {
                    String v = tail.substring(colon + 1).trim();
                    if (v.startsWith("true"))  return Boolean.TRUE;
                    if (v.startsWith("false")) return Boolean.FALSE;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
