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

    // Self-contained local target detection
    private fun isLocalTarget(host: String): Boolean {
        val cleanHost = host.split(":").firstOrNull() ?: host
        if (cleanHost.equals("localhost", ignoreCase = true)) return true
        if (cleanHost.startsWith("127.")) return true
        if (cleanHost.startsWith("10.")) return true
        if (cleanHost.startsWith("192.168.")) return true
        val ipRegex = Regex("""^172\.(1[6-9]|2\d|3[01])\.""")
        if (ipRegex.containsMatchIn(cleanHost)) return true
        return false
    }

    private fun normalizeUrl(query: String): String {
        if (query.startsWith("http://") || query.startsWith("https://")) return query
        val host = query.split("/").firstOrNull() ?: query
        return if (isLocalTarget(host)) "http://$query" else "https://$query"
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
        
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            // FIX: Updated signature for GeckoView 128+ / 153 to include perms and hasUserGesture
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let {
                    urlBar.post {
                        val currentText = urlBar.text.toString()
                        if (currentText != it) {
                            urlBar.setText(it)
                            urlBar.setSelection(it.length)
                        }
                    }
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                val uriString = uri ?: ""
                
                // Fallback to HTTP if HTTPS fails on a local host
                if (uriString.startsWith("https://")) {
                    val host = uriString.removePrefix("https://").split("/").firstOrNull() ?: ""
                    if (isLocalTarget(host)) {
                        val httpUrl = uriString.replaceFirst("https://", "http://")
                        session.loadUri(httpUrl)
                        return GeckoResult.fromValue(null)
                    }
                }
                
                // Render dark-themed error page for public internet failures
                val errorHtml = """
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            body { font-family: sans-serif; background: #121212; color: #fff; display: flex; flex-direction: column; justify-content: center; align-items: center; height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; text-align: center; }
                            h1 { font-size: 1.5em; margin-bottom: 10px; }
                            p { color: #aaa; font-size: 0.9em; word-break: break-all; }
                        </style>
                    </head>
                    <body>
                        <h1>Unable to load page</h1>
                        <p>${uriString}</p>
                        <p>Error code: ${error.code}</p>
                    </body>
                    </html>
                """.trimIndent()
                
                return GeckoResult.fromValue(errorHtml)
            }
        }
        
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                urlBar.post {
                    val currentText = urlBar.text.toString()
                    if (currentText != url) {
                        urlBar.setText(url)
                        urlBar.setSelection(url.length)
                    }
                }
            }
        }
        
        geckoView.setSession(session)
        geckoView.setAutofillEnabled(true)

        session.loadUri("https://duckduckgo.com")

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    val url = if (query.contains(".") && !query.contains(" ")) {
                        normalizeUrl(query)
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
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> session.setActive(false)
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                if (!isChangingConfigurations) session.setActive(false)
            }
            else -> session.setActive(false)
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
