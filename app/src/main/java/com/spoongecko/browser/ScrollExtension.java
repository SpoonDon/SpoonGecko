package com.spoongecko.browser;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.Map;

public final class ScrollExtension {

    private static final String TAG = "ScrollExtension";

    public static final String EXTENSION_ID = "scroll@spoongecko.browser";
    public static final String NATIVE_APP   = "spoongeckoScroll";

    private static final String MANIFEST_URI =
            "resource://android/assets/scroll/";

    private ScrollExtension() {}

    public interface InstallCallback {
        void onInstalled(@NonNull WebExtension extension);
    }

    public static void install(@NonNull WebExtensionController controller,
                               @NonNull InstallCallback callback) {
        controller.ensureBuiltIn(MANIFEST_URI, EXTENSION_ID)
                .accept(extension -> {
                    Log.i(TAG, "scroll extension installed: " + extension.id);
                    callback.onInstalled(extension);
                }, throwable -> {
                    Log.e(TAG, "failed to register scroll extension", throwable);
                });
    }

    /**
     * Attaches a message delegate to {@code session}.
     *
     * Must be called after {@code session.open(runtime)}.
     * {@code session.getWebExtensionController()} returns a
     * {@link WebExtension.SessionController}, which owns
     * {@code setMessageDelegate(WebExtension, MessageDelegate, String)}.
     */
    public static void attach(@NonNull GeckoSession session,
                              @NonNull WebExtension extension,
                              @NonNull Tab tab) {
        WebExtension.SessionController controller =
                session.getWebExtensionController();
        if (controller == null) {
            Log.w(TAG, "session controller is null; cannot attach scroll delegate");
            return;
        }
        controller.setMessageDelegate(extension, new Delegate(tab), NATIVE_APP);
        Log.d(TAG, "scroll delegate attached to tab " + tab.id);
    }

    private static final class Delegate implements WebExtension.MessageDelegate {
        private final Tab tab;

        Delegate(Tab tab) { this.tab = tab; }

        @Override
        @Nullable
        public GeckoResult<Object> onMessage(@NonNull String nativeApp,
                                             @NonNull Object message,
                                             @NonNull WebExtension.MessageSender sender) {
            if (!(message instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) message;

            Object atTopObj   = m.get("atTop");
            Object scrollYObj = m.get("scrollY");

            if (atTopObj instanceof Boolean) {
                tab.atTop = (Boolean) atTopObj;
            }
            if (scrollYObj instanceof Number) {
                tab.scrollY = ((Number) scrollYObj).intValue();
            }
            return null;
        }
    }
}
