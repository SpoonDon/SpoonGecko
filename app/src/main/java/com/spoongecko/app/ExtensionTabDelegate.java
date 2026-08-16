package com.spoongecko.app;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

public final class ExtensionTabDelegate implements WebExtension.TabDelegate {

    public interface SessionFactory {
        GeckoSession create(WebExtension.CreateTabDetails details);
    }

    private final SessionFactory factory;

    public ExtensionTabDelegate(SessionFactory factory) {
        this.factory = factory;
    }

    @Override
    public GeckoResult<GeckoSession> onNewTab(WebExtension source,
                                              WebExtension.CreateTabDetails details) {
        GeckoSession session = factory != null ? factory.create(details) : null;
        return GeckoResult.fromValue(session);
    }
}
