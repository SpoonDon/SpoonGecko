package com.spoongecko.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

        // Request Battery Optimization Exemption (Crucial for OEM RAM persistence)
        requestBatteryExemption()

        if (!::runtime.isInitialized) {
            val runtimeSettings = GeckoRuntimeSettings.Builder().build()
            runtime = GeckoRuntime.create(this, runtimeSettings)
        }

        if (!::session.isInitialized) {
            session = GeckoSession()
            session.open(runtime)
        }

        geckoView.setSession(session)
        
        val intentData = intent?.data
        val startUrl = intentData?.toString() ?: "https://www.startpage.com/"
        
        session.loadUri(startUrl)
        
        // Set High Priority to prevent GeckoView engine from freezing the session
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback for aggressive OEMs that block the direct intent
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Stop the keep-alive service when the app is opened/foregrounded
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        stopService(serviceIntent)
        
        if (::session.isInitialized) {
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        }
    }

    override fun onStop() {
        super.onStop()
        // Start the keep-alive service when the app is minimized
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        startForegroundService(serviceIntent)
        
        // Maintain high priority so the engine doesn't discard background tabs
        if (::session.isInitialized) {
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            if (::session.isInitialized) {
                session.loadUri(uri.toString())
            }
        }
    }
}
