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
import org.mozilla.geckoview.WebMessage;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the tab list and drives a single shared GeckoView.
 *
 * Adds vs. previous version:
 *   - closed-tab stack (restore-last-closed)
 *   - JS scroll hook that reports whether the deepest scroller is at top,
 *     which MainActivity consumes via SwipeRefreshLayout.setOnChildScrollUpCallback
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

    private final List<Tab> tabs = new ArrayList<>();
    private final Deque<PersistedTab> closedStack = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentIndex = -1;
    private boolean currentTabAtTop = true;

    // ------------------------------------------------------------------ JS

    /** Installed once per page load. Reports at-top state changes. */
    private static final String SCROLL_HOOK_JS =
            "(function(){if(window.__sgScrollHooked)return;window.__sgScrollHooked=true;var last=null;" +
            "function findScroller(el){var c=el;while(c&&c.nodeType===1){var s=null;try{s=getComputedStyle(c);}catch(e){}" +
            "if(s&&(s.overflowY==='auto'||s.overflowY==='scroll')&&c.scrollHeight-c.clientHeight>4)return c;" +
            "c=c.parentElement;}return document.scrollingElement||document.documentElement;}" +
            "function compute(ev){var t=null;if(ev){if(ev.composedPath&&ev.composedPath().length)t=ev.composedPath()[0];else t=ev.target;}" +
            "if(!t||t.nodeType!==1)t=document.body;var inner=findScroller(t);" +
            "var root=document.scrollingElement||document.documentElement;" +
            "return (inner.scrollTop<=1)&&(root.scrollTop<=1);}" +
            "function report(ev){try{var atTop=compute(ev);if(atTop===last)return;last=atTop;" +
            "try{window.postMessage('sgScroll:'+(atTop?'1':'0'),'*');}catch(e){}}catch(e){}}" +
            "window.__sgReport=function(ev){last=null;report(ev);};" +
            "document.addEventListener('scroll',report,{capture:true,passive:true});" +
            "window.addEventListener('scroll',report,{capture:true,passive:true});" +
            "document.addEventListener('touchstart',report,{capture:true,passive:true});" +
            "document.addEventListener('touchmove',report,{capture:true,passive:true});" +
            "report(null);})();";

    /** Called on tab switch — forces a fresh report without relying on the cache. */
    private static final String SCROLL_REPORT_JS =
            "(function(){try{if(window.__sgReport){window.__sgReport(null);return;}}catch(e){}" +
            "try{var r=document.scrollingElement||document.documentElement;" +
            "window.postMessage('sgScroll:'+(r.scrollTop<=1?'1':'0'),'*');}catch(e){}})();";

    public TabManager(MainActivity activity, GeckoView geckoView,
                      GeckoRuntime runtime, Listener listener) {
        this.activity = activity;
        this.geckoView = geckoView;
        this.runtime = runtime;
        this.listener = listener;
    }

    public List<Tab> getTabs() { return Collections.unmodifiableList(tabs); }
    public int getTabCount() { return tabs.size(); }
    public int getCurrentIndex() { return currentIndex; }
    public boolean isCurrentTabAtTop() { return currentTabAtTop; }
    public boolean hasClosedStack() { return !closedStack.isEmpty(); }

    public Tab getCurrentTab() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) return null;
        return tabs.get(currentIndex);
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
                if (tab == getCurrentTab()) {
                    currentTabAtTop = true;
                    listener.onPageStart(tab, tab.url);
                }
            }
            @Override public void onPageStop(GeckoSession s, boolean success) {
                try { s.evaluateJS(SCROLL_HOOK_JS); } catch (Exception ignored) {}
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

        // JS -> native: scroll-hook reports arrive here.
        session.setMessageDelegate(new GeckoSession.WebMessageDelegate() {
            @Override
            public GeckoResult<WebResponse> onMessage(GeckoSession s, WebMessage message) {
                if (message == null) return null;
                String data = message.getData();
                if (data == null || !data.startsWith("sgScroll:")) return null;
                final boolean atTop = data.endsWith("1");
                Tab cur = getCurrentTab();
                if (cur == null || cur.session != s) return null;
                mainHandler.post(() -> currentTabAtTop = atTop);
                return null;
            }
        });

        session.open(runtime);
        if (url != null && !url.isEmpty()) session.loadUri(url);

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

        // Ask the new page to re-report its scroll state.
        try { next.session.evaluateJS(SCROLL_REPORT_JS); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ close

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab closing = tabs.get(index);
        boolean wasCurrent = (index == currentIndex);

        // Push to restore stack before closing.
        if (closing.url != null && !closing.url.isEmpty()) {
            closedStack.offerFirst(new PersistedTab(closing.url, closing.title));
            while (closedStack.size() > CLOSED_STACK_MAX) closedStack.pollLast();
        }

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

    /** Pops the most recently closed tab back into existence. Returns false if stack empty. */
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
