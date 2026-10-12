package com.spoongecko.browser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the tab list and drives a single shared GeckoView.
 *
 * Scroll state comes from ScrollExtension (a bundled WebExtension). Each
 * session's native-message delegate updates its own Tab.atTop flag, so
 * SwipeRefreshLayout can gate pull-to-refresh on the real top of the page
 * rather than the raw touch Y coordinate.
 */
public class TabManager {

    public static final String HOME_URL = "https://duckduckgo.com/";
    private static final int THUMB_MAX_W = 480;
    private static final int THUMB_MAX_H = 1000;
    private static final int CLOSED_STACK_MAX = 20;

    public interface Listener {
        void onTabListChanged();
        void onCurrentTabChanged(Tab tab);
        void onPageStart(Tab tab, String url);
        void onPageStop(Tab tab, boolean success);
        void onProgress(Tab tab, int progress);
        void onTitle(Tab tab, String title);
        void onCanGoForward(Tab tab, boolean canGoForward);
    }

    private final MainActivity activity;
    private final GeckoView geckoView;
    private final GeckoRuntime runtime;
    private final Listener listener;
    private final ScrollExtension scrollExtension;

    private final List<Tab> tabs = new ArrayList<>();
    private final Deque<PersistedTab> closedStack = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentIndex = -1;

    public TabManager(MainActivity activity, GeckoView geckoView,
                  GeckoRuntime runtime, Listener listener) {
    this.activity = activity;
    this.geckoView = geckoView;
    this.runtime = runtime;
    this.listener = listener;
    this.scrollExtension = ScrollExtension.getOrCreate(runtime);
    this.scrollExtension.setTabProvider(this::getCurrentTab);   // <-- ADD THIS LINE
}

    public List<Tab> getTabs() { return Collections.unmodifiableList(tabs); }
    public int getTabCount() { return tabs.size(); }
    public int getCurrentIndex() { return currentIndex; }
    public boolean hasClosedStack() { return !closedStack.isEmpty(); }

    public Tab getCurrentTab() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) return null;
        return tabs.get(currentIndex);
    }

    /**
     * True when the current tab's deepest scroller and document root are
     * both at scrollTop 0. SwipeRefreshLayout's onChildScrollUpCallback
     * returns !this so pull-to-refresh is only allowed at the real top.
     *
     * Defaults to true until the extension reports otherwise — matches the
     * permissive behaviour we had before the extension shipped.
     */
    public boolean isCurrentTabAtTop() {
        Tab t = getCurrentTab();
        return t == null || t.atTop;
    }

    // ------------------------------------------------------------------ create

    public Tab createTab(String url) {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .suspendMediaWhenInactive(true)
                .build();
        GeckoSession session = new GeckoSession(settings);
        final Tab tab = new Tab(nextId.getAndIncrement(), session);
        tab.url = url == null ? "" : url;

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String u) {
                tab.url = u == null ? "" : u;
                tab.atTop = true;   // optimistic — new page starts at top
                if (tab == getCurrentTab()) listener.onPageStart(tab, tab.url);
            }
            @Override public void onPageStop(GeckoSession s, boolean success) {
                if (tab == getCurrentTab()) listener.onPageStop(tab, success);
            }
            @Override public void onProgressChange(GeckoSession s, int progress) {
                if (tab == getCurrentTab()) listener.onProgress(tab, progress);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession s, String title) {
                tab.title = title == null ? "" : title;
                if (tab == getCurrentTab()) listener.onTitle(tab, tab.title);
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean v) {
                tab.canGoBack = v;
            }
            @Override public void onCanGoForward(GeckoSession s, boolean v) {
                tab.canGoForward = v;
                if (tab == getCurrentTab()) listener.onCanGoForward(tab, v);
            }
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession s, LoadRequest request) {
                if (request == null || request.uri == null)
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                Uri uri = Uri.parse(request.uri);
                String scheme = uri.getScheme();
                if (scheme == null) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                switch (scheme.toLowerCase()) {
                    case "http": case "https": case "about":
                    case "data": case "blob":   case "file":
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                    case "javascript":
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    default:
                        LinkSchemeHelper.dispatch(activity, uri);
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
            }
            @Override public GeckoResult<GeckoSession> onNewSession(
                    GeckoSession s, String uri) {
                final String target = uri;
                mainHandler.post(() -> {
                    Tab t = createTab(target);
                    switchTo(tabs.indexOf(t));
                });
                return GeckoResult.fromValue(null);
            }
        });

        session.open(runtime);
        if (url != null && !url.isEmpty()) session.loadUri(url);

        // Bind this session to the scroll extension. Queued internally if
        // the extension is still installing.
        scrollExtension.bind(session, tab);

        tabs.add(tab);
        listener.onTabListChanged();

        if (currentIndex < 0) {
            currentIndex = 0;
            geckoView.setSession(tab.session);
            listener.onCurrentTabChanged(tab);
        }
        return tab;
    }

    // ------------------------------------------------------------------ switch

    public void switchTo(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (index == currentIndex) return;

        Tab outgoing = getCurrentTab();
        if (outgoing != null) captureThumbnail(outgoing);

        currentIndex = index;
        Tab next = tabs.get(index);
        geckoView.setSession(next.session);
        listener.onCurrentTabChanged(next);
        listener.onCanGoForward(next, next.canGoForward);
    }

    // ------------------------------------------------------------------ close

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab closing = tabs.get(index);
        boolean wasCurrent = (index == currentIndex);

        if (closing.url != null && !closing.url.isEmpty()) {
            closedStack.offerFirst(new PersistedTab(closing.url, closing.title));
            while (closedStack.size() > CLOSED_STACK_MAX) closedStack.pollLast();
        }

        scrollExtension.unbind(closing.session);
        try { closing.session.close(); } catch (Exception ignored) {}
        closing.recycle();
        tabs.remove(index);
        listener.onTabListChanged();

        if (tabs.isEmpty()) {
            currentIndex = -1;
            createTab(null);
            return;
        }
        if (wasCurrent) {
            if (index >= tabs.size()) index = tabs.size() - 1;
            currentIndex = -1;
            switchTo(index);
        } else if (index < currentIndex) {
            currentIndex--;
        }
    }

    public void closeCurrentTab() { closeTab(currentIndex); }

    public boolean restoreLastClosed() {
        PersistedTab p = closedStack.pollFirst();
        if (p == null) return false;
        String url = (p.url == null || p.url.isEmpty()) ? null : p.url;
        Tab t = createTab(url);
        t.title = p.title == null ? "" : p.title;
        switchTo(tabs.indexOf(t));
        return true;
    }

    // ------------------------------------------------------------------ misc

    public void reload() {
        Tab t = getCurrentTab();
        if (t != null) t.session.reload();
    }

    public void loadUrlInCurrentTab(String url) {
        Tab t = getCurrentTab();
        if (t == null || url == null || url.isEmpty()) return;
        t.url = url;
        t.session.loadUri(url);
    }

    public void closeAll() {
        for (Tab t : new ArrayList<>(tabs)) {
            // Unbind BEFORE closing, otherwise ScrollExtension.sessionToTab
            // grows without bound across activity restarts.
            scrollExtension.unbind(t.session);
            try { t.session.close(); } catch (Exception ignored) {}
            t.recycle();
        }
        tabs.clear();
        currentIndex = -1;
    }

    // -------------------------------------------------------------- persistence

    public List<PersistedTab> snapshot() {
        List<PersistedTab> out = new ArrayList<>();
        for (Tab t : tabs) {
            out.add(new PersistedTab(
                    t.url == null ? "" : t.url,
                    t.title == null ? "" : t.title));
        }
        return out;
    }

    public void restoreFrom(List<PersistedTab> persisted, int savedIndex) {
        if (persisted == null || persisted.isEmpty()) {
            createTab(null);
            return;
        }
        for (PersistedTab p : persisted) {
            String u = (p.url == null || p.url.isEmpty()) ? null : p.url;
            Tab t = createTab(u);
            t.title = p.title == null ? "" : p.title;
        }
        int target = Math.max(0, Math.min(savedIndex, tabs.size() - 1));
        if (target != currentIndex) {
            currentIndex = -1;
            switchTo(target);
        }
    }

    // ------------------------------------------------------------- thumbnails

    private void captureThumbnail(Tab tab) {
        try {
            if (geckoView == null) return;
            int w = geckoView.getWidth();
            int h = geckoView.getHeight();
            if (w <= 0 || h <= 0) return;
            int tw = w, th = h;
            if (tw > THUMB_MAX_W || th > THUMB_MAX_H) {
                float scale = Math.min((float) THUMB_MAX_W / tw,
                                       (float) THUMB_MAX_H / th);
                tw = Math.max(1, Math.round(tw * scale));
                th = Math.max(1, Math.round(th * scale));
            }
            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bmp);
            canvas.scale((float) tw / w, (float) th / h);
            geckoView.draw(canvas);
            tab.recycle();
            tab.thumbnail = bmp;
        } catch (Exception ignored) {}
    }
}
