package com.spoongecko.app.browser

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * Owns the GeckoSession instances, one per browser tab.
 *
 * We deliberately keep sessions alive when the UI detaches, so that when
 * the process is killed and respawned by an OEM skin, the restored session
 * (via GeckoSession.SessionState) is a warm resume, not a cold reload.
 */
class TabManager(private val runtime: GeckoRuntime) {

    private val sessions = LinkedHashMap<String, GeckoSession>()

    fun create(url: String = DEFAULT_URL): String {
        val id = UUID.randomUUID().toString()
        val session = GeckoSession()
        session.open(runtime)
        sessions[id] = session
        if (url != DEFAULT_URL) session.loadUri(url)
        return id
    }

    fun restore(id: String, state: GeckoSession.SessionState): GeckoSession {
        sessions[id]?.close()
        val session = GeckoSession()
        session.open(runtime)
        session.restoreState(state)
        sessions[id] = session
        return session
    }

    fun get(id: String): GeckoSession? = sessions[id]

    fun close(id: String) {
        sessions.remove(id)?.close()
    }

    fun closeAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    companion object {
        const val DEFAULT_URL = "about:blank"
    }
}
