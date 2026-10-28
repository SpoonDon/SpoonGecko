package com.spoongecko.browser;

import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.List;

public class TabManager {
    private static final String TAG = "TabManager";

    public interface Listener {
        void onTabsChanged();
        void onActiveTabChanged(Tab tab);
    }

    private final GeckoRuntime runtime;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private Listener listener;

    public TabManager(GeckoRuntime runtime) { this.runtime = runtime; }

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
        session.open(runtime);
        Tab tab = new Tab(session);
        tabs.add(tab);
        activeIndex = tabs.size() - 1;
        notifyChanged();
        notifyActive();
        return tab;
    }

    public Tab createTabWithState(GeckoSession.SessionState state) {
        Tab tab = createTab();
        if (state != null) {
            try { tab.session.restoreState(state); }
            catch (Exception e) { Log.w(TAG, "restoreState failed", e); }
        }
        return tab;
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

    private void notifyChanged() { if (listener != null) listener.onTabsChanged(); }
    private void notifyActive() { if (listener != null) listener.onActiveTabChanged(getActiveTab()); }
}
