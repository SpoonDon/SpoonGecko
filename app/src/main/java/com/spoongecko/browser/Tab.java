package com.spoongecko.browser;

import android.graphics.Bitmap;

import org.mozilla.geckoview.GeckoSession;

/**
 * One browser tab: session + cached UI state.
 *
 * GeckoSession is the source of truth for what's loaded; the cached
 * fields mirror what the currently-attached session would report if we
 * asked it, because GeckoSession does not expose getTitle()/canGoBack()
 * synchronously.
 */
public class Tab {

    public final long id;
    public final GeckoSession session;

    public String title = "";
    public String url = "";
    public boolean canGoBack = false;
    public boolean canGoForward = false;

    /**
     * Root-scroller position as reported by the bundled ScrollExtension
     * content script (assets/scroll/scroll.js) via sendNativeMessage.
     * Defaults to true so a brand-new or freshly-restored session is
     * allowed to pull-to-refresh until the page reports otherwise.
     *
     * Volatile because the message arrives on a GeckoView thread and is
     * read on the main thread from SwipeRefreshLayout's scroll callback.
     */
    public volatile boolean atTop = true;
    public volatile int scrollY = 0;

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

    /**
     * Called after a session is (re)attached to the GeckoView and after
     * a successful page load. The content script will correct this if the
     * page actually starts scrolled (back nav, hash, restored position).
     */
    public void resetScrollState() {
        this.scrollY = 0;
        this.atTop = true;
    }

    /** Display title with a safe fallback for empty / untitled tabs. */
    public String displayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (url != null && !url.isEmpty()) return url;
        return "New tab";
    }
}
