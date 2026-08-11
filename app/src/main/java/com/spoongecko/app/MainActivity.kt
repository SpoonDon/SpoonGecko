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

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?) {
            super.onLocationChange(session, url)
            url?.let {
                // Safely update URL bar on the main thread to catch HTTP -> HTTPS redirects
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
                if (UrlNormalizer.isLocalTarget(host)) {
                    val httpUrl = uriString.replaceFirst("https://", "http://")
                    session.loadUri(httpUrl)
                    // Return empty string to prevent error page flash before HTTP load starts
                    return GeckoResult.fromValue("")
                }
            }
            
            // FIX: Return a custom dark-themed error page for ALL other errors to prevent the "white tab"
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

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            super.onPageStart(session, url)
            // Update URL bar immediately when a new load starts
            urlBar.post {
                val currentText = urlBar.text.toString()
                if (currentText != url) {
                    urlBar.setText(url)
                    urlBar.setSelection(url.length)
                }
            }
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
        
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        
        geckoView.setSession(session)
        geckoView.setAutofillEnabled(true)

        session.loadUri("https://duckduckgo.com")

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    val url = UrlNormalizer.normalize(query)
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
