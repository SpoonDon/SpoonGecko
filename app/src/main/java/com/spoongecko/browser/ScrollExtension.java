package com.spoongecko.browser;

import android.os.Bundle;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the bundled "SpoonGecko Scroll Hook" WebExtension to per-tab
 * scroll state.
 *
 * GeckoView 157 API shape (learned the hard way):
 *   - The message delegate lives on WebExtension, not on GeckoSession or
 *     on WebExtensionController.SessionController.
 *   - One delegate handles messages from ALL sessions. To find out which
 *     tab sent a message, use MessageSender.getSession().
 *   - Therefore: register ONE delegate on install(), and keep a
 *     session -> Tab map that TabManager populates as tabs are created.
 */
public final class ScrollExtension {

    private static final String NATIVE_APP = "SpoonScroll";
    private static final String EXTENSION_URI = "resource://android/assets/scroll_hook/";

    private static volatile ScrollExtension INSTANCE;

    private volatile WebExtension extension;

    /**
     * Session-scoped lookup. Written from TabManager on the main thread,
     * read from the WebExtension message thread inside the delegate.
     * ConcurrentHashMap because the two threads don't coordinate.
     */
    private final Map<GeckoSession, Tab> sessionToTab = new ConcurrentHashMap<>();

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
     * One-time delegate registration. Fires for every message from every
     * session running the content script. Looks up the sending tab via
     * MessageSender.getSession().
     */
    private void attachDelegate(WebExtension ext) {    
        try {        
            ext.setMessageDelegate(new WebExtension.MessageDelegate() {            
                @Override            
                public void onMessage(String nativeApp,                                  
                                      Object message,                                  
                                      WebExtension.MessageSender sender) {                
                    if (sender == null) return;                
                    GeckoSession senderSession = sender.getSession();                
                    if (senderSession == null) return;                
                    Tab tab = sessionToTab.get(senderSession);                
                    if (tab == null) return;                
                    Boolean atTop = extractAtTop(message);                
                    if (atTop != null) tab.atTop = atTop;            
                }        
            }, NATIVE_APP);    
        } catch (Exception ignored) {        
            // If registration fails, tabs stay at atTop = true (permissive).    
        }
    }

    /** Called by TabManager right after a session is opened. */
    public void bind(GeckoSession session, Tab tab) {
        if (session == null || tab == null) return;
        sessionToTab.put(session, tab);
    }

    /** Called by TabManager when a tab is closed. */
    public void unbind(GeckoSession session) {
        if (session == null) return;
        sessionToTab.remove(session);
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
