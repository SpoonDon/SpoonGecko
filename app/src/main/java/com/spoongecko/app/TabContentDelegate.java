package com.spoongecko.app;

import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;

import java.lang.ref.WeakReference;

class TabContentDelegate implements GeckoSession.ContentDelegate {
    private final WeakReference<MainActivity> activityRef;
    private final GeckoSession ownSession;

    TabContentDelegate(MainActivity activity, GeckoSession session) {
        this.activityRef = new WeakReference<>(activity);
        this.ownSession = session;
    }

    public void onTitleChange(GeckoSession session, String title) {
        MainActivity activity = activityRef.get();
        if (activity != null && session == ownSession && title != null && !title.isEmpty()) {
            activity.tabTitles.put(session, title);
        }
    }

    public void onExternalResponse(GeckoSession session, WebResponse response) {
        MainActivity activity = activityRef.get();
        if (activity == null || response == null) return;
        activity.runOnUiThread(() ->
                Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show());
        DownloadManager.handleDownload(activity, response);
    }

    public void onCloseRequest(GeckoSession session) {
        MainActivity activity = activityRef.get();
        if (activity == null || session != ownSession) return;
        activity.runOnUiThread(() -> activity.handleCloseRequest(session));
    }

    public void onFullScreen(GeckoSession session, boolean fullScreen) {
        MainActivity activity = activityRef.get();
        if (activity == null || session != ownSession) return;
        activity.runOnUiThread(() -> activity.setFullscreen(fullScreen));
    }

    public void onContextMenu(GeckoSession session, int screenX, int screenY, ContextElement element) {
        MainActivity activity = activityRef.get();
        if (activity == null || element == null) return;
        activity.runOnUiThread(() -> activity.showContextMenu(element));
    }
}
