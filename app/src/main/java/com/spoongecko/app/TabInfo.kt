package com.spoongecko.app
import org.mozilla.geckoview.GeckoSession

data class TabInfo(
    val session: GeckoSession,
    var title: String = "New Tab",
    var url: String = "",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false
)
