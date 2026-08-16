package com.spoongecko.app;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

public final class ExtensionSessionTabDelegate implements WebExtension.SessionTabDelegate {

    public interface SessionCloseHandler {
        boolean close(GeckoSession session);
    }

    private final SessionCloseHandler closeHandler;

    public ExtensionSessionTabDelegate(SessionCloseHandler closeHandler) {
        this.closeHandler = closeHandler;
    }

    @Override
    public GeckoResult<AllowOrDeny> onCloseTab(WebExtension source, GeckoSession session) {
        if (closeHandler != null && closeHandler.close(session)) {
            return GeckoResult.allow();
        }
        return GeckoResult.deny();
    }

    @Override
    public GeckoResult<AllowOrDeny> onUpdateTab(
            WebExtension extension,
            GeckoSession session,
            WebExtension.UpdateTabDetails details) {
        return GeckoResult.allow();
    }
}
