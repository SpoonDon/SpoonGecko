package com.spoongecko.browser;

import android.util.Log;
import org.mozilla.geckoview.GeckoSession;
import java.util.ArrayList;
import java.util.List;

public class TabManager {
    private static final String TAG = "TabManager";

    public interface Listener {
        void onTabsChanged();
        void onActiveTabChanged(Tab tab);
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private Listener listener;

    public void setListener(Listener l) { this.listener = l; }

    public int count() { return tabs.size(); }
    public int getActiveIndex() { return activeIndex; }
    public List<Tab> getTabs() { return new ArrayList<>(tabs); }

    public Tab getActiveTab() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex);
    }

    public Tab createTab() {
        GeckoSession session = new GeckoSession();
        Tab tab = new Tab(session);
        tabs.add(tab);
        activeIndex = tabs.size() - 1;
        return tab;
    }

    public Tab createTabWithState(GeckoSession.SessionState state) {
        GeckoSession session = new GeckoSession();
        if (state != null) {
            try { 
                // CRITICAL: restoreState MUST be called BEFORE open()
                session.restoreState(state); 
            } catch (Exception e) { 
                Log.w(TAG, "restoreState failed", e); 
            }
        }
        Tab tab = new Tab(session);
        tabs.add(tab);
        activeIndex = tabs.size() - 1;
        return tab;
    }

    /**
     * [KILL] Gives a tab whose content process died a brand-new, UNOPENED session that carries
     * the tab's last saved page state, exactly like createTabWithState() does for a tab restored
     * after a restart. The tab itself (its place in the list, its opener, its url and title) is
     * untouched, so nothing in the tab list changes.
     *
     * The caller must attach the delegates to the new session and open it when the tab is shown.
     * The dead session is closed here (GeckoView has normally closed it already).
     */
    public GeckoSession replaceSession(Tab tab, GeckoSession.SessionState state) {
        final GeckoSession old = tab.session;
        final GeckoSession fresh = new GeckoSession();
        if (state != null) {
            try {
                // Same rule as in createTabWithState(): restoreState MUST precede open().
                fresh.restoreState(state);
            } catch (Exception e) {
                Log.w(TAG, "restoreState failed", e);
            }
        }
        tab.session = fresh;
        try { old.close(); } catch (Exception e) { Log.w(TAG, "close", e); }
        return fresh;
    }

    public void switchTo(int index) {
        if (index < 0 || index >= tabs.size() || index == activeIndex) return;
        activeIndex = index;
        notifyActive();
    }

    public void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.remove(index);
        try { t.session.close(); } catch (Exception e) { Log.w(TAG, "close", e); }
        if (tabs.isEmpty()) activeIndex = -1;
        else if (activeIndex >= tabs.size()) activeIndex = tabs.size() - 1;
        else if (index < activeIndex) activeIndex--;
        notifyChanged();
        notifyActive();
    }

    public void closeAll() {
        for (Tab t : tabs) { try { t.session.close(); } catch (Exception ignored) {} }
        tabs.clear();
        activeIndex = -1;
        notifyChanged();
        notifyActive();
    }

    public void notifyTabsChanged() { notifyChanged(); }
    public void notifyActiveChanged() { notifyActive(); }

    private void notifyChanged() { if (listener != null) listener.onTabsChanged(); }
    private void notifyActive() { if (listener != null) listener.onActiveTabChanged(getActiveTab()); }
}
