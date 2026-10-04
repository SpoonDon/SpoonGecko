package com.spoongecko.app.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.spoongecko.app.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.GeckoSession

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val tabManager = TabManager(GeckoRuntimeHolder.get())

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        bootstrapFirstTab()
    }

    private fun bootstrapFirstTab() {
        val restoredUrl = Prefs.loadLastUrl(getApplication()) ?: "https://duckduckgo.com"
        val id = tabManager.create(restoredUrl)
        attachDelegates(tabManager.get(id)!!, id)
        _uiState.update { it.copy(tabs = listOf(Tab(id = id, url = restoredUrl)), activeTabId = id) }
    }

    fun activeSession(): GeckoSession? =
        _uiState.value.activeTabId?.let(tabManager::get)

    fun addTab(url: String = "about:blank") {
        val id = tabManager.create(url)
        attachDelegates(tabManager.get(id)!!, id)
        _uiState.update { it.copy(tabs = it.tabs + Tab(id = id, url = url), activeTabId = id) }
    }

    fun closeTab(id: String) {
        tabManager.close(id)
        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id == id }
            val newActive = when {
                remaining.isEmpty() -> null
                state.activeTabId == id -> remaining.last().id
                else -> state.activeTabId
            }
            state.copy(tabs = remaining, activeTabId = newActive)
        }
        if (_uiState.value.tabs.isEmpty()) bootstrapFirstTab()
    }

    fun selectTab(id: String) {
        _uiState.update { it.copy(activeTabId = id) }
    }

    fun loadUrl(raw: String) {
        val url = com.spoongecko.app.util.UrlUtils.normalize(raw)
        activeSession()?.loadUri(url)
        Prefs.saveLastUrl(getApplication(), url)
    }

    fun reload() = activeSession()?.reload()
    fun stop() = activeSession()?.stop()
    fun goBack() = activeSession()?.goBack()
    fun goForward() = activeSession()?.goForward()

    private fun attachDelegates(session: GeckoSession, tabId: String) {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                updateTab(tabId) { it.copy(url = url, isLoading = true, progress = 0) }
                Prefs.saveLastUrl(getApplication(), url)
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                updateTab(tabId) { it.copy(isLoading = false, progress = 100) }
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateTab(tabId) { it.copy(progress = progress) }
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateTab(tabId) { it.copy(canGoBack = canGoBack) }
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateTab(tabId) { it.copy(canGoForward = canGoForward) }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                updateTab(tabId) { it.copy(title = title?.takeIf { t -> t.isNotBlank() } ?: it.url) }
            }
        }
    }

    private inline fun updateTab(id: String, transform: (Tab) -> Tab) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == id) transform(it) else it })
        }
    }
}

data class Tab(
    val id: String,
    val url: String = "about:blank",
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

data class BrowserUiState(
    val tabs: List<Tab> = emptyList(),
    val activeTabId: String? = null
)
