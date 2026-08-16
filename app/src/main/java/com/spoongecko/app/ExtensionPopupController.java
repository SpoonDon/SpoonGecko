package com.spoongecko.app;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

final class ExtensionPopupController {

    private final Context context;
    private final FrameLayout parent;
    private final GeckoRuntime runtime;
    private GeckoView popupView;
    private GeckoSession popupSession;

    ExtensionPopupController(Context context, FrameLayout parent, GeckoRuntime runtime) {
        this.context = context;
        this.parent = parent;
        this.runtime = runtime;
    }

    GeckoResult<GeckoSession> openPopup(boolean force) {
        if (popupSession == null) {
            createPopup();
        }
        boolean showing = isShowing();
        boolean shouldShow = force || !showing;
        setVisible(shouldShow);
        if (!shouldShow) {
            return GeckoResult.fromValue(null);
        }
        return GeckoResult.fromValue(popupSession);
    }

    void closePopup() {
        if (popupSession != null) {
            popupSession.close();
            popupSession = null;
        }
        if (popupView != null) {
            parent.removeView(popupView);
            popupView = null;
        }
    }

    private void createPopup() {
        popupView = new GeckoView(context);
        popupView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(0, 0);
        popupView.setLayoutParams(params);

        popupSession = new GeckoSession();
        popupSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            public void onCloseRequest(GeckoSession session) {
                closePopup();
            }
        });

        popupSession.open(runtime);
        popupView.setSession(popupSession);
        parent.addView(popupView);
    }

    private boolean isShowing() {
        if (popupView == null) return false;
        ViewGroup.LayoutParams params = popupView.getLayoutParams();
        return params != null && params.width > 0;
    }

    private void setVisible(boolean visible) {
        if (popupView == null) return;
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) popupView.getLayoutParams();
        if (visible) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT;
            params.height = FrameLayout.LayoutParams.MATCH_PARENT;
        } else {
            params.width = 0;
            params.height = 0;
        }
        popupView.setLayoutParams(params);
    }
}
