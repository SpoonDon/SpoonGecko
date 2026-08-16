package com.spoongecko.app;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

public final class ExtensionSessionTabDelegate implements WebExtension.SessionTabDelegate {

    private final Runnable newTabOpener;

    public ExtensionSessionTabDelegate(Runnable newTabOpener) {
        this.newTabOpener = newTabOpener;
    }

    @Override
    public GeckoResult<GeckoSession> onNewTab(WebExtension source,
                                              WebExtension.CreateTabDetails details) {
        if (newTabOpener != null) {
            newTabOpener.run();
        }
        return GeckoResult.fromValue(null);
    }

    @Override
    public GeckoResult<GeckoSession> onOpenOptionsPage(WebExtension source) {
        return GeckoResult.fromValue(null);
    }
}
