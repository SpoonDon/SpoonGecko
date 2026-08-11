package com.spoongecko.app

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

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            session.close()
            GeckoRuntimeManager.shutdown()
        }
    }
}
