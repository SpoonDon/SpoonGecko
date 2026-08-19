package com.spoongecko.app;

import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestError;

import java.lang.ref.WeakReference;

class NavigationDelegate implements GeckoSession.NavigationDelegate {

    private final WeakReference<MainActivity> activityRef;
    private final GeckoSession ownSession;

    NavigationDelegate(MainActivity activity, GeckoSession session) {
        this.activityRef = new WeakReference<>(activity);
        this.ownSession = session;
    }

    public void onCanGoBack(GeckoSession session, boolean canGoBack) {
        MainActivity activity = activityRef.get();
        if (activity != null && session == ownSession) {
            activity.canGoBackMap.put(session, canGoBack);
            if (session == activity.getCurrentSession()) {
                activity.runOnUiThread(activity::updateNavigationButtons);
            }
        }
    }

    public void onCanGoForward(GeckoSession session, boolean canGoForward) {
        MainActivity activity = activityRef.get();
        if (activity != null && session == ownSession) {
            activity.canGoForwardMap.put(session, canGoForward);
            if (session == activity.getCurrentSession()) {
                activity.runOnUiThread(activity::updateNavigationButtons);
            }
        }
    }

    public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                                                  GeckoSession.NavigationDelegate.LoadRequest request) {
        MainActivity activity = activityRef.get();
        if (activity != null
                && DownloadDispatcher.interceptNavigation(activity, session, request.uri)) {
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        return MainActivity.isAllowedScheme(request.uri)
                ? GeckoResult.allow()
                : GeckoResult.fromValue(AllowOrDeny.DENY);
    }

    public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,
                                                          GeckoSession.NavigationDelegate.LoadRequest request) {
        return MainActivity.isAllowedScheme(request.uri)
                ? GeckoResult.allow()
                : GeckoResult.fromValue(AllowOrDeny.DENY);
    }

    public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
        MainActivity activity = activityRef.get();
        if (activity == null) return GeckoResult.fromValue(null);
        if (activity.sessions.size() >= MainActivity.MAX_TABS) {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity,
                            activity.getString(R.string.tab_limit_reached, MainActivity.MAX_TABS),
                            Toast.LENGTH_SHORT).show());
            return GeckoResult.fromValue(null);
        }

        GeckoSession newSession = new GeckoSession();
        activity.attachDelegates(newSession);
        activity.sessions.add(newSession);
        activity.tabTitles.put(newSession, activity.getString(R.string.tab_new_title));
        activity.tabUrls.put(newSession, uri);
        activity.selectTab(activity.sessions.size() - 1);
        return GeckoResult.fromValue(newSession);
    }

    public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
        MainActivity activity = activityRef.get();
        if (activity == null) return GeckoResult.fromValue(null);

        if (error.code == WebRequestError.ERROR_SECURITY_BAD_CERT) {
            return GeckoResult.fromValue(InternalPages.securityWarningDataUri(uri));
        }

        activity.runOnUiThread(() ->
                Toast.makeText(activity,
                        activity.getString(R.string.error_loading_page, error.getMessage()),
                        Toast.LENGTH_LONG).show());
        return GeckoResult.fromValue(null);
    }
}
