package com.spoongecko.browser;

import org.mozilla.geckoview.GeckoSession;

public class Tab {
    public final GeckoSession session;
    public String title = "New tab";
    public String url = "";
    public boolean canGoBack = false;
    public boolean canGoForward = false;
    public GeckoSession.SessionState latestState;
    /** "Desktop site" for this tab (user agent + viewport); saved with the tab. */
    public boolean desktopMode = false;
    /** Last process-priority hint applied to this tab's session (avoids re-sending the same one). */
    public boolean priorityHigh = false;

    public Tab(GeckoSession session) {
        this.session = session;
    }
}
