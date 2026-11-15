package com.spoongecko.browser;

import org.mozilla.geckoview.GeckoSession;

public class Tab {
    public final GeckoSession session;
    public String title = "New tab";
    public String url = "";
    public boolean canGoBack = false;
    public boolean canGoForward = false;
    public GeckoSession.SessionState latestState;
    /**
     * [PERF] The raw saved session state of a restored tab that has not been
     * opened yet. Parsing a SessionState is expensive (it holds a tab's whole
     * serialized history), so with lazy restore a background tab keeps only
     * this string until it is first selected; MainActivity parses it and
     * feeds it to the session right before opening it (the same
     * restoreState-before-open order as before). Null once parsed, or when
     * the tab never had a state. While set, TabPersistence writes the string
     * out unchanged — the exact bytes the state was loaded from — so a tab
     * saved again before being opened survives a restart exactly as before.
     */
    public String pendingStateText;
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
    /**
     * [BACK] Back on this tab's first page closes it and shows {@link #opener} again. True for
     * tabs opened from another tab's link (target=_blank, window.open, "Open link in new tab").
     */
    public boolean backReturnsToOpener = false;
    /**
     * [BACK] The app just started a Back/Forward step; the next location report is its result.
     * Lets the home panel follow a step that lands on the blank home entry.
     */
    public boolean historyStepPending = false;
    /** Something has actually been shown in this tab (not just an empty window or a download). */
    public boolean hasContent = false;

    public Tab(GeckoSession session) {
        this.session = session;
    }
}
