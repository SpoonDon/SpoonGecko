package com.spoongecko.app

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.SessionState

data class TabInfo(
    val session: GeckoSession,
    var title: String = "New Tab",
    var url: String = "",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var sessionState: SessionState? = null
)
