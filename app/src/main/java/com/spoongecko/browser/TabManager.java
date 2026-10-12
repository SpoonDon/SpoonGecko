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
import org.mozilla.geckoview.WebRequestError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

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

    public boolean isCurrentTabAtTop() {
        Tab t = getCurrentTab();
        return t == null || t.atTop;
    }

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
                if (isBlank(u)) {
                    tab.url = "";
                    tab.resetScrollState();
                    return;
                }
                tab.url = u;
                tab.resetScrollState();
                if (tab == getCurrentTab()) listener.onPageStart(tab, tab.url);
            }

            @Override public void onPageStop(GeckoSession s, boolean success) {
                if (success) tab.resetScrollState();
                if (tab == getCurrentTab()) listener.onPageStop(tab, success);
            }

            @Override public void onProgressChange(GeckoSession s, int progress) {
                if (tab == getCurrentTab()) listener.onProgress(tab, progress);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession s, String title) {
                if (isBlank(tab.url) || "about:blank".equals(title)) {
                    tab.title = "";
                } else {
                    tab.title = title == null ? "" : title;
                }
                if (tab == getCurrentTab()) listener.onTitle(tab, tab.title);
            }
        });

        session.setPromptDelegate(new AuthPromptHandler(activity));

        // Scroll position is reported by the bundled ScrollExtension
        // content script (see ScrollExtension.attach below). We do NOT
        // rely on GeckoSession.ScrollDelegate: it fires only for the root
        // scroller of the visual viewport and stops firing on many pages.

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

                // ---- Extension support ----------------------------------
                // AMO download URLs carry a query string
                //   ?src=hp&utm_source=...
                // so match on the path, not the whole URL.
                if ("https".equalsIgnoreCase(scheme)) {
                    String path = uri.getPath();
                    if (path != null
                            && path.toLowerCase(Locale.US).endsWith(".xpi")) {
                        final String xpiUrl = request.uri;
                        mainHandler.post(() ->
                                ExtensionManager.getInstance().installFromUrl(xpiUrl));
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    }
                }
                // ---- End extension support ------------------------------

                switch (scheme.toLowerCase(Locale.US)) {
                    case "moz-extension":
                        // Internal WebExtension pages (options, panels).
                        // These must render in-session, never be handed off.
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW);

                    case "http": case "https": case "about":
                    case "data": case "blob":   case "file": {
                        String target = request.uri;
                        String desired = WebViewUserAgent.isLocalTarget(target)
                                ? WebViewUserAgent.VALUE
                                : null;
                        if (!Objects.equals(
                                s.getSettings().getUserAgentOverride(), desired)) {
                            s.getSettings().setUserAgentOverride(desired);
                        }
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                    }
                    case "javascript":
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    case "spoongecko": {
                        String host = uri.getHost();
                        if ("retry-http".equals(host)) {
                            String innerUrl = uri.getQueryParameter("url");
                            if (innerUrl != null && !innerUrl.isEmpty()) {
                                final String target = innerUrl;
                                mainHandler.post(() -> {
                                    applyUaForUrl(s, target);
                                    s.loadUri(target);
                                });
                            }
                        }
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    }
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

            @Override public GeckoResult<String> onLoadError(
                    GeckoSession s, String uri, WebRequestError error) {
                int category = (error == null) ? 0 : error.category;
                int code = (error == null) ? 0 : error.code;

                String categoryLabel;
                switch (category) {
                    case WebRequestError.ERROR_CATEGORY_SECURITY:
                        categoryLabel = "Security error"; break;
                    case WebRequestError.ERROR_CATEGORY_NETWORK:
                        categoryLabel = "Network error"; break;
                    case WebRequestError.ERROR_CATEGORY_CONTENT:
                        categoryLabel = "Content error"; break;
                    case WebRequestError.ERROR_CATEGORY_URI:
                        categoryLabel = "Invalid address"; break;
                    case WebRequestError.ERROR_CATEGORY_PROXY:
                        categoryLabel = "Proxy error"; break;
                    case WebRequestError.ERROR_CATEGORY_SAFEBROWSING:
                        categoryLabel = "Blocked by safe browsing"; break;
                    default:
                        categoryLabel = "Could not load page"; break;
                }

                String codeLabel;
                switch (code) {
                    case WebRequestError.ERROR_CONNECTION_REFUSED:
                        codeLabel = "Connection refused"; break;
                    case WebRequestError.ERROR_NET_TIMEOUT:
                        codeLabel = "Connection timed out"; break;
                    case WebRequestError.ERROR_UNKNOWN_HOST:
                        codeLabel = "Host not found"; break;
                    case WebRequestError.ERROR_SECURITY_SSL:
                        codeLabel = "SSL negotiation failed"; break;
                    case WebRequestError.ERROR_SECURITY_BAD_CERT:
                        codeLabel = "Bad certificate"; break;
                    case WebRequestError.ERROR_OFFLINE:
                        codeLabel = "Device is offline"; break;
                    case WebRequestError.ERROR_PORT_BLOCKED:
                        codeLabel = "Port blocked by system"; break;
                    case WebRequestError.ERROR_HTTPS_ONLY:
                        codeLabel = "Blocked: HTTPS-only mode"; break;
                    case WebRequestError.ERROR_UNKNOWN_PROTOCOL:
                        codeLabel = "Unknown protocol"; break;
                    case WebRequestError.ERROR_NET_RESET:
                        codeLabel = "Connection reset by peer"; break;
                    case WebRequestError.ERROR_NET_INTERRUPT:
                        codeLabel = "Connection interrupted"; break;
                    default:
                        codeLabel = null; break;
                }

                String safeUri = (uri == null || uri.isEmpty()) ? "" : uri;
                String detail = (codeLabel == null)
                        ? categoryLabel
                        : categoryLabel + " - " + codeLabel;

                String httpButton = "";
                boolean certFail = (code == WebRequestError.ERROR_SECURITY_BAD_CERT
                        || code == WebRequestError.ERROR_SECURITY_SSL);
                if (certFail && safeUri.startsWith("https://")
                        && isPrivateHost(safeUri)) {
                    String httpUrl = "http://" + safeUri.substring("https://".length());
                    String encoded = Uri.encode(httpUrl, ":/?&=%#");
                    httpButton =
                            "<p style=\"margin-top:20px;\">" +
                            "<a href=\"spoongecko://retry-http?url=" + encoded + "\" " +
                            "style=\"display:inline-block;padding:10px 16px;" +
                            "background:#4d6bfe;color:#ffffff;text-decoration:none;" +
                            "border-radius:8px;font-size:14px;\">" +
                            "Try over HTTP instead</a></p>";
                }

                String html =
                        "<!DOCTYPE html><html><head>" +
                        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<style>" +
                        "body{font-family:sans-serif;margin:0;padding:24px;" +
                        "background:#ffffff;color:#0d0d0d;}" +
                        "h1{font-size:20px;margin:0 0 12px 0;}" +
                        "p{font-size:14px;line-height:1.5;color:#555;}" +
                        "code{background:#f2f2f2;padding:2px 6px;border-radius:4px;" +
                        "font-size:12px;word-break:break-all;}" +
                        "</style></head><body>" +
                        "<h1>Could not load page</h1>" +
                        "<p>" + htmlEscape(detail) + "</p>" +
                        "<p><code>" + htmlEscape(safeUri) + "</code></p>" +
                        httpButton +
                        "</body></html>";
                String encodedHtml = android.util.Base64.encodeToString(
                        html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                return GeckoResult.fromValue("data:text/html;base64," + encodedHtml);
            }
        });

        if (!isBlank(url)) {
            applyUaForUrl(session, url);
        }

        // Attach the scroll reporter before opening the session so the
// content script's first message is never missed.
// The built-in extension may not have resolved yet (ensureBuiltIn is
// async), so guard against a null reference and retry on first load.
WebExtension scrollExt = ExtensionManager.getInstance().getScrollExtension();
if (scrollExt != null) {
    ScrollExtension.attach(session, scrollExt, tab);
} else {
    // Fallback: attach on the first page-start callback, by which
    // time ensureBuiltIn has almost certainly completed.
    final Tab fTab = tab;
    session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
        @Override
        public void onPageStart(GeckoSession s, String url) {
            GeckoSession.ProgressDelegate.super.onPageStart(s, url);
            WebExtension ext = ExtensionManager.getInstance()
                    .getScrollExtension();
            if (ext != null) {
                ScrollExtension.attach(s, ext, fTab);
            }
        }
    });
}

        session.open(runtime);
        if (!isBlank(url)) {
            session.loadUri(url);
        }

        tabs.add(tab);

        // Notify *after* currentIndex has been corrected so the tab badge
        // never flashes "0" for a frame on a fresh list.
        if (currentIndex < 0) {
            currentIndex = 0;
            geckoView.setSession(tab.session);
            listener.onCurrentTabChanged(tab);
        }

        listener.onTabListChanged();
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
        next.resetScrollState();
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

        if (tabs.isEmpty()) {
            currentIndex = -1;
            // createTab() fires onTabListChanged / onCurrentTabChanged
            // itself, so the badge ends up consistent at 1/1.
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

        // Fire *after* index math so updateTabBadge() reads a consistent
        // (current, size) pair. Fixes the "2/1" symptom.
        listener.onTabListChanged();
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
        applyUaForUrl(t.session, url);
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

    // ------------------------------------------------------------- helpers

    private static void applyUaForUrl(GeckoSession session, String url) {
        String desired = WebViewUserAgent.isLocalTarget(url)
                ? WebViewUserAgent.VALUE
                : null;
        if (!Objects.equals(session.getSettings().getUserAgentOverride(), desired)) {
            session.getSettings().setUserAgentOverride(desired);
        }
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static boolean isPrivateHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            if (host.equals("localhost") || host.equals("127.0.0.1")) return true;
            String[] parts = host.split("\\.");
            if (parts.length != 4) return false;
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) return true;
            if (a == 127) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 169 && b == 254) return true;
            return false;
        } catch (Exception e) {
            return false;
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
