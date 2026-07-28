package com.spoongecko.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var runtime: GeckoRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)

        // Initialize GeckoRuntime with minimal settings (No telemetry)
        if (!::runtime.isInitialized) {
            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .telemetryEnabled(false) // Explicitly disable telemetry
                .consoleOutputEnabled(true) // Keep for debugging, can disable later
                .build()
            
            runtime = GeckoRuntime.create(this, runtimeSettings)
        }

        // Initialize GeckoSession
        if (!::session.isInitialized) {
            session = GeckoSession()
            session.open(runtime)
        }

        // Attach session to view and load default URL
        geckoView.setSession(session)
        
        // Handle incoming intents (if opened via another app)
        val intentData = intent?.data
        val startUrl = intentData?.toString() ?: "https://www.startpage.com/"
        
        session.loadUri(startUrl)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            if (::session.isInitialized) {
                session.loadUri(uri.toString())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // We do not close the session here to allow for potential future 
        // background/foreground lifecycle management.
    }
}
