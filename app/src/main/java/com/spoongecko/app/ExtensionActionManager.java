package com.spoongecko.app;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ExtensionActionManager implements WebExtension.ActionDelegate {

    public interface PopupOpener {
        GeckoResult<GeckoSession> openPopup(boolean force);
    }

    private static final ExtensionActionManager INSTANCE = new ExtensionActionManager();
    private final Map<String, WebExtension.Action> defaultActions = new ConcurrentHashMap<>();
    private PopupOpener popupOpener;

    public static ExtensionActionManager getInstance() {
        return INSTANCE;
    }

    private ExtensionActionManager() {}

    public void setPopupOpener(PopupOpener opener) {
        this.popupOpener = opener;
    }

    public void register(WebExtension extension) {
        if (extension != null) {
            extension.setActionDelegate(this);
        }
    }

    public boolean click(WebExtension extension) {
        if (extension == null) return false;
        WebExtension.Action action = defaultActions.get(extension.id);
        if (action == null) return false;
        action.click();
        return true;
    }

    @Override
    public void onBrowserAction(WebExtension extension, GeckoSession session,
                                WebExtension.Action action) {
        if (session == null && extension != null && action != null) {
            defaultActions.put(extension.id, action);
        }
    }

    @Override
    public void onPageAction(WebExtension extension, GeckoSession session,
                             WebExtension.Action action) {
        if (session == null && extension != null && action != null) {
            defaultActions.put(extension.id, action);
        }
    }

    @Override
    public GeckoResult<GeckoSession> onOpenPopup(WebExtension extension,
                                                 WebExtension.Action action) {
        return openPopup(true);
    }

    @Override
    public GeckoResult<GeckoSession> onTogglePopup(WebExtension extension,
                                                   WebExtension.Action action) {
        return openPopup(false);
    }

    private GeckoResult<GeckoSession> openPopup(boolean force) {
        if (popupOpener == null) return GeckoResult.fromValue(null);
        return popupOpener.openPopup(force);
    }
}
