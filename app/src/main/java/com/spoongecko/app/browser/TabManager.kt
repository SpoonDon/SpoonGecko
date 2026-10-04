package com.spoongecko.app.browser

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * Owns the GeckoSession instances, one per browser tab.
 *
 * We deliberately keep sessions alive when the UI detaches, so that when
 * the process is killed and respawned by an OEM skin, the restored session
 * (via GeckoSession.SessionState) is a warm resume, not a cold reload.
 *
 * IMPORTANT: session.open() is asynchronous. Calling loadUri() immediately
 * after open() is a race that has been observed to SIGSEGV libxul.so on
 * cold starts on Xiaomi HyperOS and several other OEM ROMs. We defer the
 * first load until OpenDelegate.onReady() fires.
 */
class TabManager(private val runtime: GeckoRuntime) {

    private val sessions = LinkedHashMap<String, GeckoSession>()

    fun create(url: String = DEFAULT_URL): String {
        val id = UUID.randomUUID().toString()
        val session = GeckoSession()

        // Defer the initial load until the session is actually ready.
        // onReady() fires on the Gecko thread; loadUri() is thread-safe.
        if (url != DEFAULT_URL) {
            session.openDelegate = object : GeckoSession.OpenDelegate {
                override fun onReady(session: GeckoSession) {
                    session.openDelegate = null
                    runCatching { session.loadUri(url) }
                        .onFailure { Log.e(TAG, "deferred loadUri failed for $url", it) }
                }
            }
        }

        session.open(runtime)
        sessions[id] = session
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
        private const val TAG = "TabManager"
        const val DEFAULT_URL = "about:blank"
    }
}
