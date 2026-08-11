package com.spoongecko.app

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
        session = GeckoSession()
        session.open(runtime)
        geckoView.setSession(session)

        session.loadUri("https://duckduckgo.com")

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    val url = if (query.contains(".") && !query.contains(" ")) {
                        if (query.startsWith("http")) query else "https://$query"
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
        // Official GeckoView best practice: Mark session as active when UI is visible
        session.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        // Official GeckoView best practice: Mark session as inactive to release GPU/rendering resources
        session.setActive(false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Handle Android OS memory pressure signals to prevent the app from being killed
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI is no longer visible, ensure heavy rendering resources are released
                session.setActive(false)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // System is running low on memory.
                // GeckoView's internal lowMemoryDetection handles engine caches,
                // but we force the session inactive if the app is backgrounded.
                if (!isChangingConfigurations) {
                    session.setActive(false)
                }
            }
            else -> {
                // TRIM_MEMORY_BACKGROUND, TRIM_MEMORY_COMPLETE, etc.
                // App is in background and system is starving. Release everything possible.
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
