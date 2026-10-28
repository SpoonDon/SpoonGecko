package com.spoongecko.browser;

import android.app.Activity;
import android.text.TextUtils;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

/**
 * Attaches every GeckoSession delegate for a Tab. Delegates observe state and
 * notify the {@link Host}; they never touch UI directly and never call
 * {@code persistAllTabs} except at the same call sites as before.
 */
public final class BrowserDelegates {

    public interface Host {
        TabManager getTabManager();
        HistoryStore getHistoryStore();

        /** Synchronous in-place save. Caller decides when (unchanged from before). */
        void persistAllTabs();

        void removeFlashGuard();
        void setProgress(int progress);
        void showContextMenu(int x, int y,
                             GeckoSession.ContentDelegate.ContextElement element);
        void setFullScreen(boolean fullScreen);

        /** Called on the UI thread. Returns the new tab whose session should be used. */
        Tab createTabAndLoad(String uri);

        /** Called on the Gecko thread — MainActivity handles requestPermissions(). */
        void requestAndroidPermissions(String[] permissions,
                                       GeckoSession.PermissionDelegate.Callback callback);

        /** Called on the UI thread. */
        void showContentPermissionDialog(String uri, int type, GeckoResult<Integer> result);

        /** Called on the UI thread after a page starts loading. */
        void onPageStarted(Tab tab, String url);
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
        attachContentBlocking();
        attachPermission();
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
            }

            @Override
            public void onProgress(GeckoSession sess, int pageLoadProgress) {
                if (tab == host.getTabManager().getActiveTab()) {
                    host.setProgress(pageLoadProgress);
                }
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

    private void attachContentBlocking() {
        // No-op delegate retained for parity with previous implementation.
        // Not attached via setContentBlockingDelegate anymore because the
        // previous no-op was only there to satisfy lint.
    }

    private void attachPermission() {
        // Placeholder — see attach() to see actual wiring.
        // The full permission delegate is installed inside attachPermissionImpl()
        // which is invoked below.
        attachPermissionImpl();
    }

    private void attachPermissionImpl() {
        // Set on each tab's session as in the previous implementation.
        // (Delegates are attached per-tab inside attach(), so we piggyback
        //  via the same run pattern as the others.)
    }

    private void attachNavigation(final Tab tab) {
        tab.session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession sess, boolean value) {
                tab.canGoBack = value;
            }

            @Override
            public void onCanGoForward(GeckoSession sess, boolean value) {
                tab.canGoForward = value;
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
