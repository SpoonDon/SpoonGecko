package com.spoongecko.app

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError

class MainActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var session: GeckoSession

    // 1. Helper to detect local network IPs (RFC1918 + localhost)
    private fun isLocalHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val localIpRegex = Regex("""^(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3})$""")
        return localIpRegex.matches(host)
    }

    // 2. Smart URL normalization based on README requirements
    private fun normalizeUrl(query: String): String {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return query
        }
        val host = query.split("/").firstOrNull() ?: query
        return if (isLocalHost(host)) {
            "http://$query" // Local targets default to http://
        } else {
            "https://$query" // Public domains default to https://
        }
    }

    // 3. Navigation Delegate to handle redirects and error fallbacks
    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?) {
            super.onLocationChange(session, url)
            url?.let {
                // FIX 2: Update URL bar on redirects (e.g., HTTP -> HTTPS)
                val currentText = urlBar.text.toString()
                if (currentText != it) {
                    urlBar.setText(it)
                }
            }
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            // FIX 1 & README Requirement: Fallback to HTTP if HTTPS fails on a local host
            // (e.g., self-signed cert, connection refused, or HSTS issues on LAN)
            if (uri != null && uri.startsWith("https://")) {
                val host = uri.removePrefix("https://").split("/").firstOrNull() ?: ""
                if (isLocalHost(host)) {
                    val httpUrl = uri.replaceFirst("https://", "http://")
                    session.loadUri(httpUrl)
                    return GeckoResult.fromValue(null) // Handled, suppress white screen/error page
                }
            }
            return super.onLoadError(session, uri, error)
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            super.onPageStart(session, url)
            // Optional: You could add a loading spinner/progress bar here
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            super.onPageStop(session, success)
            // Optional: Hide loading spinner
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        urlBar = findViewById(R.id.url_bar)

        val runtime = GeckoRuntimeManager.getRuntime(applicationContext)
        val sessionSettings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(true)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .allowJavascript(true)
            .build()

        session = GeckoSession(sessionSettings)
        session.open(runtime)
        
        // Bind delegates to handle URL updates and error fallbacks
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        
        geckoView.setSession(session)
        geckoView.setAutofillEnabled(true)

        session.loadUri("https://duckduckgo.com")

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    val url = if (query.contains(".") && !query.contains(" ")) {
                        normalizeUrl(query) // Use smart normalization
                    } else {
                        "https://duckduckgo.com/?q=$query"
                    }
                    session.loadUri(url)
                }
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
                true
            } else false
        }
    }

    override fun onStart() {
        super.onStart()
        session.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        session.setActive(false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                session.setActive(false)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                if (!isChangingConfigurations) {
                    session.setActive(false)
                }
            }
            else -> {
                session.setActive(false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            session.close()
            GeckoRuntimeManager.shutdown()
        }
    }
}
