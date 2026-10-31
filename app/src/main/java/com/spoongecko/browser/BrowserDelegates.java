package com.spoongecko.browser;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest;
import org.mozilla.geckoview.WebResponse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BrowserDelegates {

    /** Schemes Gecko itself can load; everything else goes to the system. */
    private static final Set<String> BROWSER_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "about", "data", "blob", "file", "ws", "wss", "javascript"));

    public interface Host {
        TabManager getTabManager();
        HistoryStore getHistoryStore();
        void persistAllTabs();
        void removeFlashGuard();
        void setProgressBarVisible(boolean visible);
        void setLoadProgress(int progress);                                  // [H5]
        void showContextMenu(int x, int y,
                             GeckoSession.ContentDelegate.ContextElement element);
        void setFullScreen(boolean fullScreen);
        Tab createTabAndLoad(String uri);
        void requestAndroidPermissions(String[] permissions,
                                       GeckoSession.PermissionDelegate.Callback callback);
        void showContentPermissionDialog(String uri, int type, GeckoResult<Integer> result);
        void showMediaPermissionDialog(String uri,
                GeckoSession.PermissionDelegate.MediaSource[] video,
                GeckoSession.PermissionDelegate.MediaSource[] audio,
                GeckoSession.PermissionDelegate.MediaCallback callback);    // [H3]
        void onTabLocationChanged(Tab tab, String url);                      // [H4]
        void openExternalUri(String uri);                                    // [M3]
        void downloadFile(String url, String mime, String contentDisposition); // [M3]
        void onSessionGone(Tab tab);                                         // [M9]
        void onPageStarted(Tab tab, String url);
        void updateToolbarState();
    }

    private final Activity activity;
    private final Host host;
    private final BrowserPrompts prompts;    // [H1]

    public BrowserDelegates(Activity activity, Host host, BrowserPrompts prompts) {
        this.activity = activity;
        this.host = host;
        this.prompts = prompts;
    }

    public void attach(final Tab tab) {
        attachProgress(tab);
        attachContent(tab);
        attachPermission(tab);
        attachNavigation(tab);
        // [H1] Without a PromptDelegate, alert()/confirm()/prompt(), <select>
        // menus, <input type=file>, HTTP auth and share requests all did nothing.
        tab.session.setPromptDelegate(prompts);
    }

    private void attachProgress(final Tab tab) {
        tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onSessionStateChange(GeckoSession sess, GeckoSession.SessionState st) {
                tab.latestState = st;
                if (tab == host.getTabManager().getActiveTab()) host.persistAllTabs();
            }

            @Override
            public void onPageStart(GeckoSession sess, String url) {
                if (url == null || url.startsWith("about:")) return;
                tab.url = url;
                activity.runOnUiThread(() -> host.onPageStarted(tab, url));
            }

            // [H5] The progress bar used to stay at 0% forever because onProgressChange
            // was never implemented.
            @Override
            public void onProgressChange(GeckoSession sess, int progress) {
                if (tab == host.getTabManager().getActiveTab()) {
                    activity.runOnUiThread(() -> host.setLoadProgress(progress));
                }
            }

            @Override
            public void onPageStop(GeckoSession sess, boolean success) {
                if (tab == host.getTabManager().getActiveTab()) host.persistAllTabs();
                activity.runOnUiThread(() -> host.setProgressBarVisible(false));
            }
        });
    }

    private void attachContent(final Tab tab) {
        tab.session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession sess, String title) {
                tab.title = title == null ? "" : title;
            }

            @Override
            public void onFirstContentfulPaint(GeckoSession session) {
                host.removeFlashGuard();
                if (!TextUtils.isEmpty(tab.url)) {
                    host.getHistoryStore().recordVisit(tab.url, tab.title);
                }
            }

            @Override
            public void onFullScreen(GeckoSession sess, boolean fullScreen) {
                activity.runOnUiThread(() -> host.setFullScreen(fullScreen));
            }

            @Override
            public void onCloseRequest(GeckoSession sess) {
                activity.runOnUiThread(() -> {
                    int idx = host.getTabManager().getTabs().indexOf(tab);
                    if (idx >= 0) host.getTabManager().closeTab(idx);
                });
            }

            @Override
            public void onContextMenu(GeckoSession sess, int x, int y,
                                      GeckoSession.ContentDelegate.ContextElement element) {
                activity.runOnUiThread(() -> host.showContextMenu(x, y, element));
            }

            // [M3] Gecko could not render the response (Content-Disposition:
            // attachment, unknown type, ...) — i.e. a download. Route it to the
            // system DownloadManager via the host.
            @Override
            public void onExternalResponse(GeckoSession sess, WebResponse response) {
                final String uri = response.uri;
                final String mime = header(response, "content-type");
                final String disposition = header(response, "content-disposition");
                activity.runOnUiThread(() -> host.downloadFile(uri, mime, disposition));
            }

            // [M9] Content process death. The session is closed and unusable;
            // the host re-opens it and reloads the last URL.
            @Override
            public void onCrash(GeckoSession sess) {
                activity.runOnUiThread(() -> host.onSessionGone(tab));
            }

            @Override
            public void onKill(GeckoSession sess) {
                activity.runOnUiThread(() -> host.onSessionGone(tab));
            }
        });
    }

    private void attachPermission(final Tab tab) {
        tab.session.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession session, ContentBlocking.BlockEvent event) {}
        });

        tab.session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(
                    GeckoSession session, String[] permissions,
                    GeckoSession.PermissionDelegate.Callback callback) {
                host.requestAndroidPermissions(permissions, callback);
            }

            @Override
            public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession session,
                    GeckoSession.PermissionDelegate.ContentPermission perm) {
                if (perm == null) {
                    return GeckoResult.fromValue(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                }
                final GeckoResult<Integer> result = new GeckoResult<>();
                final String uri = perm.uri;
                final int type = perm.permission;
                activity.runOnUiThread(() -> host.showContentPermissionDialog(uri, type, result));
                return result;
            }

            // [H3] Camera/microphone requests used to be granted with no user
            // interaction at all (callback.grant(video[0], audio[0]) straight
            // away) — a serious privacy hole. Ask the user first.
            @Override
            public void onMediaPermissionRequest(
                    GeckoSession session, String uri,
                    GeckoSession.PermissionDelegate.MediaSource[] video,
                    GeckoSession.PermissionDelegate.MediaSource[] audio,
                    GeckoSession.PermissionDelegate.MediaCallback callback) {
                activity.runOnUiThread(() ->
                        host.showMediaPermissionDialog(uri, video, audio, callback));
            }
        });
    }

    private void attachNavigation(final Tab tab) {
        tab.session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession sess, boolean value) {
                tab.canGoBack = value;
                if (tab == host.getTabManager().getActiveTab()) host.updateToolbarState();
            }

            @Override
            public void onCanGoForward(GeckoSession sess, boolean value) {
                tab.canGoForward = value;
                if (tab == host.getTabManager().getActiveTab()) host.updateToolbarState();
            }

            // [H4] onPageStart only fires for top-level document loads; it never
            // fires for redirects already in flight or SPA pushState navigations,
            // so the URL bar used to show stale addresses. onLocationChange is
            // the authoritative signal.
            @Override
            public void onLocationChange(GeckoSession sess, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                    Boolean hasUserGesture) {
                if (url == null) return;
                tab.url = "about:blank".equals(url) ? "" : url;
                if (tab == host.getTabManager().getActiveTab()) {
                    activity.runOnUiThread(() -> host.onTabLocationChanged(tab, url));
                }
            }

            // [M3] mailto:, tel:, sms:, geo:, market:, intent:, ... were silently
            // failing inside Gecko. Hand them to the system and cancel the load.
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession sess, LoadRequest request) {
                final String uri = request.uri;
                final String scheme = uriScheme(uri);
                if (scheme == null || BROWSER_SCHEMES.contains(scheme)) {
                    return null;   // null == ALLOW (unhandled)
                }
                activity.runOnUiThread(() -> host.openExternalUri(uri));
                return GeckoResult.deny();
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession source, String finalUri) {
                final String target = (finalUri == null || finalUri.isEmpty())
                        ? "about:blank" : finalUri;
                final GeckoResult<GeckoSession> result = new GeckoResult<>();
                activity.runOnUiThread(() -> {
                    Tab newTab = host.createTabAndLoad(target);
                    result.complete(newTab.session);
                });
                return result;
            }
        });
    }

    private static String uriScheme(String uri) {
        if (uri == null) return null;
        try {
            String scheme = Uri.parse(uri).getScheme();
            return scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Case-insensitive header lookup; WebMessage.headers is a plain map. */
    private static String header(WebResponse response, String lowerCaseName) {
        if (response.headers == null) return null;
        for (Map.Entry<String, String> e : response.headers.entrySet()) {
            if (e.getKey() != null
                    && e.getKey().toLowerCase(Locale.ROOT).equals(lowerCaseName)) {
                return e.getValue();
            }
        }
        return null;
    }
}
