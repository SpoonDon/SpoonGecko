package com.spoongecko.browser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var geckoRuntime: GeckoRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.geckoView)

        // 1. Create the GeckoRuntime.
        geckoRuntime = GeckoRuntime.create(this)

        // 2. Create and open a GeckoSession.
        geckoSession = GeckoSession()
        geckoSession.open(geckoRuntime)
        geckoView.setSession(geckoSession)

        // 3. Load a default page.
        geckoSession.loadUri("https://www.mozilla.org")

        // 4. Install the bundled extension (only once).
        installBuiltInExtension()

        // 5. Ask the user to whitelist SpoonGecko from battery optimisation.
        requestIgnoreBatteryOptimizations()
    }

    private fun installBuiltInExtension() {
        // The extension is located at app/src/main/assets/extension/
        val extensionUrl = "resource://android/assets/extension/"
        val extensionId = "spoongecko@example.com"

        geckoRuntime.webExtensionController
            .ensureBuiltIn(extensionUrl, extensionId)
            .accept({ extension ->
                Log.i("SpoonGecko", "Extension installed: ${extension?.id}")
            }, { throwable ->
                Log.e("SpoonGecko", "Extension install failed", throwable)
            })
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            // If the intent is not resolvable (some OEMs hide it), fall back to the general settings page.
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onDestroy() {
        geckoSession.close()
        super.onDestroy()
    }
}
