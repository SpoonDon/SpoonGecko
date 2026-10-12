package com.spoongecko.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.Map;

/**
 * Bundled built-in WebExtension that reports the page's root-scroller
 * position to the Java side.
 *
 * Why this exists: GeckoSession.ScrollDelegate fires only for the root
 * scroller of the visual viewport, and in practice stops firing on many
 * pages (SPA app shells with overflow:auto, sticky headers, CSS
 * transforms). The old code relied on it alone and got stuck with
 * atTop == true mid-page, which caused SwipeRefreshLayout to trigger a
 * reload from anywhere in the document.
 *
 * The injected content script (assets/scroll/scroll.js) reads the real
 * document.scrollingElement.scrollTop on every scroll event and posts it
 * via browser.runtime.sendNativeMessage("spoongeckoScroll", ...). The
 * Java side attaches a WebExtension.MessageDelegate per session.
 */
public final class ScrollExtension {

    public static final String EXTENSION_ID = "scroll@spoongecko.browser";
    public static final String NATIVE_APP   = "spoongeckoScroll";

    private static final String MANIFEST_URI =
            "resource://android/assets/scroll/manifest.json";

    private ScrollExtension() {}

    /** Registers the built-in with the runtime. Call exactly once. */
    public static void register(@NonNull WebExtensionController controller) {
        WebExtension extension = new WebExtension(
                MANIFEST_URI,
                /* isBuiltIn */ true,
                /* allowedInPrivateBrowsing */ true);
        controller.registerBuiltIn(extension);
    }

    /**
     * Attaches a message delegate to {@code session} that updates
     * {@code tab}'s scroll state from the content script's reports.
     */
    public static void attach(@NonNull GeckoSession session, @NonNull Tab tab) {
        session.setMessageDelegate(new Delegate(tab), NATIVE_APP);
    }

    private static final class Delegate implements WebExtension.MessageDelegate {
        private final Tab tab;

        Delegate(Tab tab) { this.tab = tab; }

        @Override
        public void onMessage(@NonNull WebExtension.MessageSender sender,
                              @Nullable Object message) {
            if (!(message instanceof Map)) return;
            Map<?, ?> m = (Map<?, ?>) message;

            Object atTopObj   = m.get("atTop");
            Object scrollYObj = m.get("scrollY");

            if (atTopObj instanceof Boolean) {
                tab.atTop = (Boolean) atTopObj;
            }
            if (scrollYObj instanceof Number) {
                tab.scrollY = ((Number) scrollYObj).intValue();
            }
        }
    }
}
