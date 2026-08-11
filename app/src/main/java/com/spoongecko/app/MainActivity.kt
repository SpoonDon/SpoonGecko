package com.spoongecko.app

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSessionSettings

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var session: GeckoSession

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
        geckoView.setSession(session)
        geckoView.setAutofillEnabled(true)

        session.loadUri("https://duckduckgo.com")

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()

                if (query.isNotEmpty()) {
                    val url = UrlNormalizer.normalize(query)
                    if (url.isNotEmpty()) {
                        session.loadUri(url)
                    }
                }

                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(urlBar.windowToken, 0)

                true
            } else {
                false
            }
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
