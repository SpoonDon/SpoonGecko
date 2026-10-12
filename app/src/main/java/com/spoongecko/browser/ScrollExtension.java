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
 *
 * API note (GeckoView 157):
 *   - WebExtension is no longer constructed manually. Use
 *     WebExtensionController.ensureBuiltIn(String uri, String id) or
 *     installBuiltIn(String uri) to register the bundled extension.
 *   - GeckoSession no longer has setMessageDelegate(MessageDelegate, String).
 *     Instead use session.getWebExtensionController()
 *         .setMessageDelegate(WebExtension, MessageDelegate, String).
 *   - The content script must be authorized to send native messages via
 *     the "nativeMessagingFromContent" permission in manifest.json.
 */
public final class ScrollExtension {

    public static final String EXTENSION_ID = "scroll@spoongecko.browser";
    public static final String NATIVE_APP   = "spoongeckoScroll";

    private static final String MANIFEST_URI =
            "resource://android/assets/scroll/";

    private ScrollExtension() {}

    /**
     * Registers the built-in with the runtime. Call exactly once.
     *
     * ensureBuiltIn only installs if the extension is not already present
     * with the same version, so calling this at every startup is safe
     * (though installBuiltIn would also work, it re-installs each time).
     */
    public static void register(@NonNull WebExtensionController controller) {
        controller.ensureBuiltIn(MANIFEST_URI, EXTENSION_ID)
                .accept(
                        extension -> {
                            android.util.Log.i("ScrollExtension",
                                    "scroll extension ready: " + extension.id);
                        },
                        throwable -> {
                            android.util.Log.e("ScrollExtension",
                                    "failed to register scroll extension", throwable);
                        });
    }

    /**
     * Attaches a message delegate to {@code session} that updates
     * {@code tab}'s scroll state from the content script's reports.
     *
     * @param extension the WebExtension instance returned by
     *                  ensureBuiltIn / installBuiltIn
     */
    public static void attach(@NonNull GeckoSession session,
                              @NonNull WebExtension extension,
                              @NonNull Tab tab) {
        session.getWebExtensionController().setMessageDelegate(
                extension,
                new Delegate(tab),
                NATIVE_APP);
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
