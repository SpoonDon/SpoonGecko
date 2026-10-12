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

    /** Callback invoked once the scroll extension is available. */
    public interface InstallCallback {
        void onInstalled(@NonNull WebExtension extension);
    }

    /**
     * Registers the built-in and delivers the resolved WebExtension via
     * {@code callback}. Safe to call at every startup: ensureBuiltIn only
     * installs when the extension is missing or has a different version.
     */
    public static void install(@NonNull WebExtensionController controller,
                               @NonNull InstallCallback callback) {
        controller.ensureBuiltIn(MANIFEST_URI, EXTENSION_ID)
                .accept(callback::onInstalled,
                        throwable -> android.util.Log.e("ScrollExtension",
                                "failed to register scroll extension", throwable));
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
