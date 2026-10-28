package com.spoongecko.browser;

import android.app.Activity;
import android.text.TextUtils;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

public final class BrowserDelegates {

    public interface Host {
        TabManager getTabManager();
        HistoryStore getHistoryStore();
        void persistAllTabs();
        void removeFlashGuard();
        void setProgressBarVisible(boolean visible);
        void showContextMenu(int x, int y,
                             GeckoSession.ContentDelegate.ContextElement element);
        void setFullScreen(boolean fullScreen);
        Tab createTabAndLoad(String uri);
        void requestAndroidPermissions(String[] permissions,
                                       GeckoSession.PermissionDelegate.Callback callback);
        void showContentPermissionDialog(String uri, int type, GeckoResult<Integer> result);
        void onPageStarted(Tab tab, String url);
        void updateToolbarState();
    }

    private final Activity activity;
    private final Host host;

    public BrowserDelegates(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void attach(final Tab tab) {
        attachProgress(tab);
        attachContent(tab);
        attachPermission(tab);
        attachNavigation(tab);
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

            @Override
            public void onMediaPermissionRequest(
                    GeckoSession session, String uri,
                    GeckoSession.PermissionDelegate.MediaSource[] video,
                    GeckoSession.PermissionDelegate.MediaSource[] audio,
                    GeckoSession.PermissionDelegate.MediaCallback callback) {
                activity.runOnUiThread(() -> {
                    GeckoSession.PermissionDelegate.MediaSource v =
                            (video != null && video.length > 0) ? video[0] : null;
                    GeckoSession.PermissionDelegate.MediaSource a =
                            (audio != null && audio.length > 0) ? audio[0] : null;
                    callback.grant(v, a);
                });
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
}
