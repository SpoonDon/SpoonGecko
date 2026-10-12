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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the list of open tabs and drives a single shared GeckoView.
 *
 * Design notes:
 *   - One GeckoView, N GeckoSession objects. Switching tabs calls
 *     GeckoView.setSession(newTab.session). This is the same pattern Firefox
 *     for Android uses and keeps native view memory roughly constant.
 *   - Background tabs keep loading; sessions hold their own cookies,
 *     history, scroll state and DOM. Nothing is destroyed on switch.
 *   - Every session has its own delegates bound to its Tab. Delegates set
 *     cached fields on the Tab (title, url, canGoBack, canGoForward) even
 *     when the tab is not current, and only notify the Listener when the
 *     reporting tab is the current one.
 */
public class TabManager {

    public static final String HOME_URL = "https://duckduckgo.com/";

    private static final int THUMB_MAX_W = 480;
    private static final int THUMB_MAX_H = 1000;

    /** All UI-facing events flow through this. Implemented by MainActivity. */
    public interface Listener {
        void onTabListChanged();
        void onCurrentTabChanged(Tab tab);
        void onPageStart(Tab tab, String url);
        void onPageStop(Tab tab, boolean success);
        void onProgress(Tab tab, int progress);
        void onTitle(Tab tab, String title);
        void onCanGoBack(Tab tab, boolean canGoBack);
        void onCanGoForward(Tab tab, boolean canGoForward);
    }

    private final MainActivity activity;
    private final GeckoView geckoView;
    private final GeckoRuntime runtime;
    private final Listener listener;

    private final List<Tab> tabs = new ArrayList<>();
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

    public Tab getCurrentTab() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) return null;
        return tabs.get(currentIndex);
    }

    // ------------------------------------------------------------------ create

    /**
     * Create a new tab. If this is the first tab (currentIndex == -1) it
     * becomes the current tab immediately; otherwise it is appended without
     * stealing focus — callers that want to jump to it must call switchTo().
     *
     * A null / empty url produces a blank tab (the home screen overlay shows
     * through the empty GeckoView).
     */
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
            @Override public void onCanGoBack(GeckoSession s, boolean value) {
                tab.canGoBack = value;
                if (tab == getCurrentTab()) listener.onCanGoBack(tab, value);
            }
            @Override public void onCanGoForward(GeckoSession s, boolean value) {
                tab.canGoForward = value;
                if (tab == getCurrentTab()) listener.onCanGoForward(tab, value);
            }

            /**
             * Handles link-scheme freeze fix (magnet:/tel:/intent:/custom) and
             * web-scheme allow-list. Identical semantics to the previous
             * single-tab MainActivity, now per-session.
             */
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession s, LoadRequest request) {
                if (request == null || request.uri == null) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                Uri uri = Uri.parse(request.uri);
                String scheme = uri.getScheme();
                if (scheme == null) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                String lower = scheme.toLowerCase();
                switch (lower) {
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

            /**
             * A page called window.open() / used target=_blank. Open a real
             * new tab. Posted to main thread so we're not mutating the tab
             * list from the Gecko thread.
             */
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
        if (url != null && !url.isEmpty()) {
            session.loadUri(url);
        }

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
        listener.onCanGoBack(next, next.canGoBack);
        listener.onCanGoForward(next, next.canGoForward);
    }

    // ------------------------------------------------------------------ close

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab closing = tabs.get(index);
        boolean wasCurrent = (index == currentIndex);

        try { closing.session.close(); } catch (Exception ignored) {}
        closing.recycle();
        tabs.remove(index);
        listener.onTabListChanged();

        if (tabs.isEmpty()) {
            currentIndex = -1;
            Tab replacement = createTab(null);
            // createTab auto-promotes the first tab to current.
            return;
        }

        if (wasCurrent) {
            if (index >= tabs.size()) index = tabs.size() - 1;
            currentIndex = -1;              // force switchTo to do real work
            switchTo(index);
        } else if (index < currentIndex) {
            currentIndex--;
        }
    }

    public void closeCurrentTab() {
        closeTab(currentIndex);
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

    /**
     * Best-effort snapshot of the GeckoView for the tab switcher. Silently
     * no-ops if the view has not been laid out yet (e.g. during restore).
     */
    private void captureThumbnail(Tab tab) {
        try {
            if (geckoView == null) return;
            int w = geckoView.getWidth();
            int h = geckoView.getHeight();
            if (w <= 0 || h <= 0) return;

            int tw = w, th = h;
            if (tw > THUMB_MAX_W || th > THUMB_MAX_H) {
                float scale = Math.min(
                        (float) THUMB_MAX_W / tw,
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
