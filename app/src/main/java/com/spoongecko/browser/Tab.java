package com.spoongecko.browser;

import android.graphics.Bitmap;

import org.mozilla.geckoview.GeckoSession;

/**
 * One browser tab: session + cached UI state.
 *
 * GeckoSession is the source of truth for what's loaded; the cached fields
 * mirror what the currently-attached session would report if we asked it,
 * because GeckoSession does not expose getTitle()/canGoBack() synchronously.
 */
public class Tab {

    public final long id;
    public final GeckoSession session;

    public String title = "";
    public String url = "";
    public boolean canGoBack = false;
    public boolean canGoForward = false;

    /**
     * Set by the ScrollExtension content script on every scroll event.
     * True when the deepest scrollable ancestor and the document root are
     * both at scrollTop 0. Defaults true so pull-to-refresh is allowed
     * until the first real report arrives.
     *
     * Volatile because it's written from the WebExtension message thread
     * and read from the main thread in SwipeRefreshLayout's scroll callback.
     */
    public volatile boolean atTop = true;
// Add:
    public volatile int scrollY = 0;
/** Called from TabManager after a session is (re)attached to the GeckoView. */
    public void resetScrollState() {
    // Freshly attached sessions start at the top of the document;
    // ScrollDelegate will correct this if the page restores a
    // scroll position, but we must not leave a stale `false` here.
        this.scrollY = 0;
        this.atTop = true;
    }

    /** Small preview bitmap for the tab switcher; may be null. */
    public Bitmap thumbnail = null;

    public Tab(long id, GeckoSession session) {
        this.id = id;
        this.session = session;
    }

    public void recycle() {
        if (thumbnail != null && !thumbnail.isRecycled()) {
            thumbnail.recycle();
        }
        thumbnail = null;
    }

    /** Display title with a safe fallback for empty / untitled tabs. */
    public String displayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (url != null && !url.isEmpty()) return url;
        return "New tab";
    }
}
