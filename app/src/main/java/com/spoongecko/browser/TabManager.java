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
 * Scroll state comes from GeckoSession.ScrollDelegate — GeckoView fires
 * onScrollChanged(session, scrollX, scrollY) on every main-frame scroll,
 * so we can gate pull-to-refresh on the real top of the page instead of the
 * raw touch coordinate. No WebExtension, no JS injection.
 *
 * Limitation: the delegate reports the root scroller. Pages that scroll
 * exclusively via a nested overflow:auto container (some SPA feeds) will
 * report scrollY == 0 while scrolled, which permits pull-to-refresh there.
 * That's an acceptable trade-off — the alternative (JS hook) needs a
 * WebExtension round-trip that has proven unreliable in this build.
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
    public boolean hasClosedStack() { return !closedStack.isEmpty(); }

    public Tab getCurrentTab() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) return null;
        return tabs.get(currentIndex);
    }

    /**
     * True when the current tab's root scroller is at scrollY == 0.
     * SwipeRefreshLayout's onChildScrollUpCallback returns !this so
     * pull-to-refresh only fires at the real top.
     */
    public boolean isCurrentTabAtTop() {
        Tab t = getCurrentTab();
        return t == null || t.atTop;
    }

    /** True for empty / about:blank URLs that should be treated as a home tab. */
    private static boolean isBlank(String url) {
        return url == null || url.isEmpty() || "about:blank".equals(url);
    }

    // ------------------------------------------------------------------ create

    public Tab createTab(String url) {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .suspendMediaWhenInactive(true)
                .build();
        GeckoSession session = new GeckoSession(settings);
        final Tab tab = new Tab(nextId.getAndIncrement(), session);
        tab.url = isBlank(url) ? "" : url;

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String u) {
                // GeckoView fires onPageStart with "about:blank" the first
                // time a freshly-opened session is attached. Treat that as
                // a home tab: keep tab.url empty and don't touch the URL bar.
                if (isBlank(u)) {
                    tab.url = "";
                    tab.atTop = true;
                    return;
                }
                tab.url = u;
                tab.atTop = true;
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
                // Suppress the "about:blank" title for home tabs.
                if (isBlank(tab.url) || "about:blank".equals(title)) {
                    tab.title = "";
                } else {
                    tab.title = title == null ? "" : title;
                }
                if (tab == getCurrentTab()) listener.onTitle(tab, tab.title);
            }
        });
        session.setPromptDelegate(new AuthPromptHandler(activity));
        // Root-scroller position. Fires whenever the page scrolls. Reported
        // values are in CSS pixels and can be negative during overscroll on
        // some builds, so we clamp to <= 0 rather than == 0.
        session.setScrollDelegate(new GeckoSession.ScrollDelegate() {
            @Override public void onScrollChanged(GeckoSession s, int scrollX, int scrollY) {
                tab.atTop = scrollY <= 0;
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
                        
            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String uri,
                                                   org.mozilla.geckoview.WebRequestError error) {
                int category = (error == null) ? 0 : error.category;
                String msg;
                switch (category) {
                    case org.mozilla.geckoview.WebRequestError.ErrorCategory.SECURITY:
                        msg = "Security error (TLS/certificate)";
                        break;
                    case org.mozilla.geckoview.WebRequestError.ErrorCategory.NETWORK:
                        msg = "Network error (connection failed)";
                        break;
                    case org.mozilla.geckoview.WebRequestError.ErrorCategory.CONTENT:
                        msg = "Content error";
                        break;
                    case org.mozilla.geckoview.WebRequestError.ErrorCategory.URI:
                        msg = "Invalid address";
                        break;
                    case org.mozilla.geckoview.WebRequestError.ErrorCategory.PROXY:
                        msg = "Proxy error";
                        break;
                    case org.mozilla.geckoview.WebRequestError.ErrorCategory.SAFEBROWSING:
                        msg = "Blocked by safe browsing";
                        break;
                    default:
                        msg = "Could not load page";
                        break;
                }
                String safeUri = (uri == null || uri.isEmpty()) ? "" : uri;
                String html =
                        "<!DOCTYPE html><html><head>" +
                        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<style>" +
                        "body{font-family:sans-serif;margin:0;padding:24px;" +
                        "background:#ffffff;color:#0d0d0d;}" +
                        "h1{font-size:20px;margin:0 0 12px 0;}" +
                        "p{font-size:14px;line-height:1.5;color:#555;}" +
                        "code{background:#f2f2f2;padding:2px 6px;border-radius:4px;" +
                        "font-size:12px;word-break:break-all;}" +
                        "</style></head><body>" +
                        "<h1>Could not load page</h1>" +
                        "<p>" + msg + "</p>" +
                        "<p><code>" + safeUri + "</code></p>" +
                        "</body></html>";
                String encoded = android.util.Base64.encodeToString(
                        html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                return GeckoResult.fromValue("data:text/html;base64," + encoded);
            }            
        });

        session.open(runtime);
        if (!isBlank(url)) session.loadUri(url);

        tabs.add(tab);
        listener.onTabListChanged();

        if (currentIndex < 0) {
            currentIndex = 0;
            geckoView.setSession(tab.session);
            listener.onCurrentTabChanged(tab);
        }
        return tab;
    }

    private static boolean isHttpOrHttps(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
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
