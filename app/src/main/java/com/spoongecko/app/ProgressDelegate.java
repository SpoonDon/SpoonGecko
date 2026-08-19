package com.spoongecko.app;

import android.widget.ProgressBar;

import org.mozilla.geckoview.GeckoSession;

import java.lang.ref.WeakReference;

class ProgressDelegate implements GeckoSession.ProgressDelegate {
    private final WeakReference<MainActivity> activityRef;
    private final GeckoSession ownSession;

    ProgressDelegate(MainActivity activity, GeckoSession session) {
        this.activityRef = new WeakReference<>(activity);
        this.ownSession = session;
    }

    public void onPageStart(GeckoSession session, String url) {
        MainActivity activity = activityRef.get();
        if (activity == null) return;
        activity.tabUrls.put(session, url);
        if (session != activity.getCurrentSession()) return;
        activity.runOnUiThread(() -> {
            activity.progressBar.setVisibility(ProgressBar.VISIBLE);
            if (url != null && url.startsWith("data:")) {
                activity.urlBar.setText("");
            } else {
                activity.urlBar.setText(url);
            }
        });
    }

    public void onPageStop(GeckoSession session, boolean success) {
        MainActivity activity = activityRef.get();
        if (activity == null) return;
        if (success) {
            String url = activity.tabUrls.get(session);
            String title = activity.tabTitles.get(session);
            HistoryStore.record(activity, url, title);
        }
        if (session != activity.getCurrentSession()) return;
        activity.runOnUiThread(() -> activity.progressBar.setVisibility(ProgressBar.GONE));
    }

    public void onProgressChange(GeckoSession session, int progress) {
        MainActivity activity = activityRef.get();
        if (activity == null || session != activity.getCurrentSession()) return;
        activity.runOnUiThread(() -> activity.progressBar.setProgress(progress));
    }

    public void onSecurityChange(GeckoSession session,
                                 GeckoSession.ProgressDelegate.SecurityInformation securityInfo) {}

    public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState sessionState) {
        MainActivity activity = activityRef.get();
        if (activity != null) {
            int index = activity.sessions.indexOf(session);
            if (index >= 0 && index < MainActivity.MAX_PERSISTED_TABS) {
                activity.sessionStates.put(session, sessionState);
            } else {
                activity.sessionStates.remove(session);
            }
        }
    }
}
