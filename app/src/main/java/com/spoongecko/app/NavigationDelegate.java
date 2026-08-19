package com.spoongecko.app;

import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebRequestError;

import java.lang.ref.WeakReference;
import java.net.URL;

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
            if (session == activity.getCurrentSession()) activity.runOnUiThread(activity::updateNavigationButtons);
        }
    }

    public void onCanGoForward(GeckoSession session, boolean canGoForward) {
        MainActivity activity = activityRef.get();
        if (activity != null && session == ownSession) {
            activity.canGoForwardMap.put(session, canGoForward);
            if (session == activity.getCurrentSession()) activity.runOnUiThread(activity::updateNavigationButtons);
        }
    }

    public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                                                  GeckoSession.NavigationDelegate.LoadRequest request) {
        MainActivity activity = activityRef.get();
        if (activity != null
                && DownloadDispatcher.interceptNavigation(activity, session, request.uri)) {
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        return MainActivity.isAllowedScheme(request.uri) ? GeckoResult.allow() : GeckoResult.fromValue(AllowOrDeny.DENY);
    }

    public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession session,
                                                          GeckoSession.NavigationDelegate.LoadRequest request) {
        return MainActivity.isAllowedScheme(request.uri) ? GeckoResult.allow() : GeckoResult.fromValue(AllowOrDeny.DENY);
    }

    public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
        MainActivity activity = activityRef.get();
        if (activity == null) return GeckoResult.fromValue(null);
        if (activity.sessions.size() >= MainActivity.MAX_TABS) {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, activity.getString(R.string.tab_limit_reached, MainActivity.MAX_TABS), Toast.LENGTH_SHORT).show());
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
            String host = null;
            try { URL url = new URL(uri); host = url.getHost(); } catch (Exception ignored) {}
            String safeHost = MainActivity.escapeHtml(host != null ? host : uri);
            String safeUri = MainActivity.escapeJs(uri);
            String errorPage = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;"
                    + "min-height:100vh;margin:0;background:#111319;color:#E1E2E8}"
                    + ".card{max-width:420px;padding:32px;text-align:center}"
                    + "h2{font-size:20px;margin:0 0 8px 0;color:#FFB4AB}"
                    + "p{font-size:14px;color:#C4C6D0;margin:0 0 24px 0;line-height:1.5}"
                    + ".host{font-family:monospace;word-break:break-all;color:#E1E2E8}"
                    + "button{background:#4d6bfe;color:#fff;border:none;padding:12px 24px;"
                    + "border-radius:8px;font-size:16px;cursor:pointer}"
                    + "button:hover{background:#3b54d0}"
                    + ".cancel{background:none;color:#4d6bfe;border:1px solid #4d6bfe;margin-top:12px}"
                    + ".cancel:hover{background:#2B3042}"
                    + "</style></head><body><div class='card'>"
                    + "<h2>Security Warning</h2>"
                    + "<p>The certificate for <span class='host'>" + safeHost + "</span> is not trusted.<br>"
                    + "Connecting to this site may expose your information.</p>"
                    + "<button onclick='proceed()'>Proceed (unsafe)</button><br>"
                    + "<button class='cancel' onclick='cancel()'>Go Back</button>"
                    + "<script>"
                    + "function proceed(){"
                    + "document.addCertException(true).then(function(){"
                    + "location.replace('" + safeUri + "');"
                    + "});}"
                    + "function cancel(){history.back();}"
                    + "</script></div></body></html>";
            return GeckoResult.fromValue("data:text/html;charset=utf-8,"
                    + MainActivity.encodeForDataUri(errorPage));
        }

        activity.runOnUiThread(() ->
                Toast.makeText(activity, activity.getString(R.string.error_loading_page, error.getMessage()), Toast.LENGTH_LONG).show()
        );
        return GeckoResult.fromValue(null);
    }
}
