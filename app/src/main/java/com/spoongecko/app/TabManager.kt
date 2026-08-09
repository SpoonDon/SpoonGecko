package com.spoongecko.app

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

class TabManager(
    private val runtime: GeckoRuntime,
    private val geckoView: GeckoView,
    private val onActiveTabChanged: (TabInfo?) -> Unit,
    private val onLastTabClosed: () -> Unit,
    private val onSessionCreated: (TabInfo) -> Unit
) {
    val tabs = mutableListOf<TabInfo>()
    var activeTab: TabInfo? = null

    fun createNewSession() {
        val session = GeckoSession(GeckoSessionSettings.Builder().suspendMediaWhenInactive(true).build())
        session.open(runtime)
        val tab = TabInfo(session)
        tabs.add(tab)
        onSessionCreated(tab) // Triggers delegate attachment
        switchToSession(tab)
        session.loadUri("data:text/html;charset=utf-8,<html><head><meta name='color-scheme' content='dark'><style>body{background-color:#121212;margin:0;}</style></head><body></body></html>")
    }

    fun switchToSession(tab: TabInfo) {
        tabs.forEach { it.session.setActive(it == tab) }
        if (geckoView.session != tab.session) geckoView.setSession(tab.session)
        activeTab = tab
        tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        onActiveTabChanged(tab)
    }

    fun closeSession(tab: TabInfo) {
        tab.session.close()
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            activeTab = null
            onLastTabClosed()
        } else {
            switchToSession(tabs.last())
        }
    }
}
