package com.spoongecko.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.spoongecko.app.browser.BrowserViewModel
import com.spoongecko.app.ui.BrowserScreen
import com.spoongecko.app.ui.theme.SpoonGeckoTheme
import com.spoongecko.app.util.OemHelper
import com.spoongecko.app.util.Prefs

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()

        setContent {
            SpoonGeckoTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onRequestBatteryExemption = { maybePromptBatteryExemption() },
                    onRequestOemAutostart = { OemHelper.openOemAutostartSettings(this) }
                )
            }
        }

        maybePromptBatteryExemption()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun maybePromptBatteryExemption() {
        if (!Prefs.isBatteryPromptShown(this) && !OemHelper.isIgnoringBatteryOptimizations(this)) {
            Prefs.setBatteryPromptShown(this)
            OemHelper.requestIgnoreBatteryOptimizations(this)
        }
    }
}
