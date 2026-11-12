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

    /** Created because a page asked for a new window (target=_blank, window.open). */
    public boolean openedByPage = false;
    /**
     * The session was handed to GeckoView (which opens it itself) and is not open yet. Until it
     * is, it must be neither opened nor attached to the view by the app: GeckoView rejects a
     * session that is already open with "Must use an unopened GeckoSession instance".
     */
    public boolean awaitingOpen = false;
    /** Address to load once the APP opens the session (only when GeckoView does not adopt it). */
    public String pendingUrl;
    /** The tab that opened this one, if any. */
    public Tab opener;
    /** Something has actually been shown in this tab (not just an empty window or a download). */
    public boolean hasContent = false;

    public Tab(GeckoSession session) {
        this.session = session;
    }
}
