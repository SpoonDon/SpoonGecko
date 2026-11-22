package com.spoongecko.browser;

import org.mozilla.geckoview.GeckoSession;

public class Tab {
    /**
     * The tab's GeckoSession. Not final any more: when the page's content process dies (Android
     * reclaimed it in the background, or the page crashed it) the dead session is unusable, so
     * TabManager.replaceSession() gives the SAME tab a fresh session that carries its saved state.
     * Always read it through the tab, never keep your own reference to it.
     */
    public GeckoSession session;
    /**
     * [INCOGNITO] This tab is an incognito tab: its session was created in GeckoView's private
     * mode (cookies, cache and site data are kept apart from normal tabs and are gone when the
     * tab closes), and the app records nothing about it: no history, no saved tab, no preview.
     * Set once when the tab is created. GeckoView cannot switch a session between private and
     * normal after it exists, so a tab never changes kind, and a replacement session made for it
     * (see TabManager.replaceSession) is private too.
     */
    public boolean isPrivate = false;
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
    /**
     * [TABS] Small screenshot of the page, shown on this tab's card in the tab switcher. Kept in
     * memory only (never saved to disk, never part of the saved tab state) and taken when the
     * switcher is opened. Null until then, or for a tab that shows the home page.
     */
    public android.graphics.Bitmap thumbnail;

    /**
     * [KILL] Address to load right after the app opens this tab's session, for a tab that has an
     * address but no saved page state to restore (the state was never reported, or could not be
     * read back). Consumed by MainActivity.openSessionIfNeeded().
     */
    public String reloadOnOpen;
    /**
     * [KILL] How many times this tab's page process died within a short time, and when it last
     * did. A page that keeps killing its process must not be reloaded forever
     * (see MainActivity.onSessionGone).
     */
    public int deaths = 0;
    public long lastDeathAt = 0L;

    public Tab(GeckoSession session) {
        this.session = session;
    }
}
