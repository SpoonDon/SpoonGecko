package com.spoongecko.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.util.regex.Pattern

data class TabInfo(
    val session: GeckoSession, 
    var title: String = "New Tab", 
    var url: String = "", 
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: org.mozilla.geckoview.GeckoView
    private lateinit var urlBar: EditText
    private lateinit var extensionManager: ExtensionManager
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    
    private val tabs = mutableListOf<TabInfo>()
    private lateinit var activeTab: TabInfo

    private val runtime by lazy { GeckoRuntimeManager.getRuntime(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        extensionManager = ExtensionManager(runtime, this)
        extensionManager.setupDelegates()

        geckoView = findViewById(R.id.gecko_view)
        urlBar = findViewById(R.id.url_bar)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)

        requestBatteryExemption()
        setupUIListeners()
        setupSystemBackButton()
        createNewSession()
    }

    private fun setupSystemBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
    }

    private fun setupUIListeners() {
        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadUrlOrSearch(v.text.toString())
                true
            } else false
        }

        btnBack.setOnClickListener { handleBackNavigation() }
        btnForward.setOnClickListener { 
            if (::activeTab.isInitialized && activeTab.canGoForward) {
                activeTab.session.goForward() 
            }
        }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { openTabManager() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenuOptions() }
    }

    private fun handleBackNavigation() {
        if (!::activeTab.isInitialized) {
            exitApp()
            return
        }

        if (activeTab.canGoBack) {
            activeTab.session.goBack()
        } else {
            if (tabs.size > 1) {
                closeSession(activeTab)
            } else {
                showExitConfirmation()
            }
        }
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Exit Spoon Gecko?")
            .setMessage("Are you sure you want to close the browser?")
            .setPositiveButton("Exit") { _, _ -> exitApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exitApp() {
        GeckoRuntimeManager.shutdown()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid()) 
    }

    private fun showMenuOptions() {
        val options = arrayOf(
            "Extensions", 
            "Clear Browsing Data",
            "Exit App"
        )

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showExtensionsMenu()
                    1 -> Toast.makeText(this, "Coming soon!", Toast.LENGTH_SHORT).show()
                    2 -> exitApp()
                }
            }
            .show()
    }

    private fun showExtensionsMenu() {
        val options = arrayOf(
            "Add-ons Store",
            "Check for Updates",
            "Extensions Dashboard"
        )

        AlertDialog.Builder(this)
            .setTitle("Extensions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { createNewSession(); activeTab.session.loadUri("https://addons.mozilla.org/firefox/") }
                    1 -> { extensionManager.checkForUpdates(); Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show() }
                    2 -> { 
                        extensionManager.openFirstExtensionDashboard(
                            onSuccess = { url -> createNewSession(); activeTab.session.loadUri(url) },
                            onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
                        )
                    }
                }
            }
            .show()
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (::activeTab.isInitialized && activeTab.canGoBack) 1.0f else 0.5f
        btnForward.alpha = if (::activeTab.isInitialized && activeTab.canGoForward) 1.0f else 0.5f
        btnBack.isEnabled = ::activeTab.isInitialized && activeTab.canGoBack
        btnForward.isEnabled = ::activeTab.isInitialized && activeTab.canGoForward
    }

    private fun loadUrlOrSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val urlPattern = Pattern.compile("^[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,}$")
        val isUrl = trimmed.startsWith("http") || urlPattern.matcher(trimmed).matches()

        if (isUrl) activeTab.session.loadUri(if (trimmed.startsWith("http")) trimmed else "https://$trimmed")
        else activeTab.session.loadUri("https://www.startpage.com/sp/search?query=$trimmed")
        
        urlBar.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun createNewSession() {
        val session = GeckoSession(GeckoSessionSettings.Builder().suspendMediaWhenInactive(true).build())
        session.open(runtime) 
        val tab = TabInfo(session)
        tabs.add(tab)
        setupDelegates(tab)
        switchToSession(tab)
        session.loadUri("https://www.startpage.com/")
    }

    private fun setupDelegates(tab: TabInfo) {
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            // ROBUST XPI INTERCEPTOR: Catches direct .xpi links AND AMO download redirects
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (uri.endsWith(".xpi", ignoreCase = true) || 
                    (uri.contains("addons.mozilla.org") && uri.contains("/downloads/"))) {
                    
                    runtime.webExtensionController.install(uri)
                        .accept { ext ->
                            runOnUiThread { Toast.makeText(this@MainActivity, "Installed: ${ext?.metaData?.name}", Toast.LENGTH_SHORT).show() }
                        }
                        .exceptionally { throwable ->
                            runOnUiThread { Toast.makeText(this@MainActivity, "Install failed: ${throwable.message}", Toast.LENGTH_SHORT).show() }
                            null
                        }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                url?.let { tab.url = it; if (tab == activeTab) runOnUiThread { urlBar.setText(it) } }
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                if (tab == activeTab) runOnUiThread { updateNavButtons() }
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                if (tab == activeTab) runOnUiThread { updateNavButtons() }
            }
        }
        tab.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { title?.let { tab.title = it } }
        }
    }

    private fun switchToSession(tab: TabInfo) {
        for (t in tabs) { t.session.setActive(t == tab) }
        if (geckoView.session != tab.session) geckoView.setSession(tab.session)
        activeTab = tab
        urlBar.setText(tab.url)
        tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        updateNavButtons()
    }

    private fun closeSession(tab: TabInfo) {
        tab.session.close(); tabs.remove(tab)
        if (tabs.isEmpty()) exitApp() else switchToSession(tabs.last())
    }

    private fun openTabManager() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_tabs, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_tabs).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = TabAdapter(tabs, activeTab,
                onClick = { switchToSession(it); bottomSheet.dismiss() },
                onClose = { closeSession(it); bottomSheet.dismiss(); openTabManager() })
        }
        view.findViewById<ImageButton>(R.id.btn_new_tab).setOnClickListener { createNewSession(); bottomSheet.dismiss() }
        bottomSheet.setContentView(view); bottomSheet.show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) } 
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onStart() { super.onStart(); stopService(Intent(this, KeepAliveService::class.java)) }
    override fun onStop() { super.onStop(); startForegroundService(Intent(this, KeepAliveService::class.java)) }
    override fun onPause() { super.onPause(); if (::activeTab.isInitialized) activeTab.session.setActive(false) }
    override fun onResume() { 
        super.onResume()
        if (::activeTab.isInitialized) activeTab.session.setActive(true)
        extensionManager.checkForUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            GeckoRuntimeManager.shutdown()
        }
    }
}
