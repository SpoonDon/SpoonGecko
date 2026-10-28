package com.spoongecko.browser;

import org.mozilla.geckoview.GeckoSession;

public class Tab {
    public final GeckoSession session;
    public String title = "New tab";
    public String url = "";
    public boolean canGoBack = false;
    public boolean canGoForward = false;
    public GeckoSession.SessionState latestState;

    public Tab(GeckoSession session) {
        this.session = session;
    }
}
