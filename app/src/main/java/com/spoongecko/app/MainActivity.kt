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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.regex.Pattern

data class TabInfo(val session: GeckoSession, var title: String = "New Tab", var url: String = "")

class MainActivity : AppCompatActivity() {

    companion object {
        private var geckoRuntime: GeckoRuntime? = null
    }

    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var mainLayout: ConstraintLayout
    
    private val tabs = mutableListOf<TabInfo>()
    private lateinit var activeTab: TabInfo

    private val runtime: GeckoRuntime
        get() {
            if (geckoRuntime == null) {
                val cbSettings = org.mozilla.geckoview.ContentBlocking.Settings.Builder()
                    .categories(
                        org.mozilla.geckoview.ContentBlocking.ANTI_TRACKING or 
                        org.mozilla.geckoview.ContentBlocking.ANTI_CRYPTO_MINING or 
                        org.mozilla.geckoview.ContentBlocking.ANTI_FINGERPRINTING or 
                        org.mozilla.geckoview.ContentBlocking.SAFE_BROWSING_ALL
                    )
                    .cookieBehavior(org.mozilla.geckoview.ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                    .build()

                val runtimeSettings = GeckoRuntimeSettings.Builder()
                    .contentBlocking(cbSettings)
                    .build()

                geckoRuntime = GeckoRuntime.create(applicationContext, runtimeSettings)
            }
            return geckoRuntime!!
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.main_layout)
        geckoView = findViewById(R.id.gecko_view)
        urlBar = findViewById(R.id.url_bar)

        requestBatteryExemption()

        setupUIListeners()
        setupExtensionPrompts() 
        applyMenuPosition() 
        createNewSession()
    }

    private fun setupExtensionPrompts() {
        // The modern GeckoView 153+ API for extension prompts
        runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Install Extension?")
                        .setMessage("Do you want to install ${extension.metaData.name}?")
                        .setPositiveButton("Install") { _, _ -> 
                            // Grant all permissions for seamless installation
                            result.complete(WebExtension.PermissionPromptResponse(true, false, false)) 
                        }
                        .setNegativeButton("Cancel") { _, _ -> 
                            result.complete(WebExtension.PermissionPromptResponse(false, false, false)) 
                        }
                        .setCancelable(false)
                        .show()
                }
                return result
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        })

        // The modern delegate for tracking installation state
        runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Installed: ${extension.metaData.name}", Toast.LENGTH_SHORT).show()
                }
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

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { activeTab.session.goBack() }
        findViewById<ImageButton>(R.id.btn_forward).setOnClickListener { activeTab.session.goForward() }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { openTabManager() }
        
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { 
            showMenuOptions()
        }
    }

    private fun showMenuOptions() {
        val prefs = getSharedPreferences("SpoonGeckoPrefs", Context.MODE_PRIVATE)
        val isCurrentlyBottom = prefs.getBoolean("menu_at_bottom", true)
        
        val options = arrayOf(
            if (isCurrentlyBottom) "✓ Menu at Bottom" else "Move Menu to Bottom",
            if (!isCurrentlyBottom) "✓ Menu at Top" else "Move Menu to Top",
            "Add-ons Store",         
            "Check for Updates",     
            "Extensions Dashboard",  
            "Clear Browsing Data"    
        )

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0, 1 -> {
                        val newValue = (which == 0)
                        if (newValue != isCurrentlyBottom) {
                            prefs.edit().putBoolean("menu_at_bottom", newValue).apply()
                            applyMenuPosition()
                        }
                    }
                    2 -> { 
                        createNewSession()
                        activeTab.session.loadUri("https://addons.mozilla.org/android/")
                    }
                    3 -> { 
                        runtime.webExtensionController.list().accept { extensions ->
                            if (extensions.isEmpty()) {
                                runOnUiThread { Toast.makeText(this@MainActivity, "No extensions to update.", Toast.LENGTH_SHORT).show() }
                                return@accept
                            }
                            
                            var pendingUpdates = extensions.size
                            for (ext in extensions) {
                                try {
                                    runtime.webExtensionController.update(ext).accept { updated ->
                                        pendingUpdates--
                                        if (updated != null) {
                                            runOnUiThread { Toast.makeText(this@MainActivity, "Updated: ${updated.metaData.name}", Toast.LENGTH_SHORT).show() }
                                        }
                                        if (pendingUpdates == 0) {
                                            runOnUiThread { Toast.makeText(this@MainActivity, "All extensions up to date.", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                } catch (e: Exception) {
                                    pendingUpdates--
                                    if (pendingUpdates == 0) {
                                        runOnUiThread { Toast.makeText(this@MainActivity, "All extensions up to date.", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }
                        }
                    }
                    4 -> { 
                        runtime.webExtensionController.list().accept { extensions ->
                            if (extensions.isNotEmpty()) {
                                // Open the first extension's options page (e.g. uBlock Origin)
                                runtime.webExtensionController.openOptionsPage(extensions[0])
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this, "No extensions installed.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    5 -> {
                        Toast.makeText(this, "Coming in Phase 5!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun applyMenuPosition() {
        val prefs = getSharedPreferences("SpoonGeckoPrefs", Context.MODE_PRIVATE)
        val isBottom = prefs.getBoolean("menu_at_bottom", true)
        
        val constraintSet = ConstraintSet()
        constraintSet.clone(mainLayout)
        
        constraintSet.clear(R.id.bottom_nav, ConstraintSet.TOP)
        constraintSet.clear(R.id.bottom_nav, ConstraintSet.BOTTOM)
        constraintSet.clear(R.id.gecko_view, ConstraintSet.TOP)
        constraintSet.clear(R.id.gecko_view, ConstraintSet.BOTTOM)

        if (isBottom) {
            constraintSet.connect(R.id.bottom_nav, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.gecko_view, ConstraintSet.BOTTOM, R.id.bottom_nav, ConstraintSet.TOP)
            constraintSet.connect(R.id.gecko_view, ConstraintSet.TOP, R.id.top_bar, ConstraintSet.BOTTOM)
        } else {
            constraintSet.connect(R.id.bottom_nav, ConstraintSet.TOP, R.id.top_bar, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.gecko_view, ConstraintSet.TOP, R.id.bottom_nav, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.gecko_view, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
        
        constraintSet.applyTo(mainLayout)
    }

    private fun loadUrlOrSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        
        val urlPattern = Pattern.compile("^[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,}$")
        val isUrl = trimmed.startsWith("http") || urlPattern.matcher(trimmed).matches()

        if (isUrl) {
            val finalUrl = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
            activeTab.session.loadUri(finalUrl)
        } else {
            activeTab.session.loadUri("https://www.startpage.com/sp/search?query=$trimmed")
        }
        
        urlBar.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun createNewSession() {
        val settings = GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(true)
            .build()
            
        val session = GeckoSession(settings)
        session.open(runtime) 
        
        val tab = TabInfo(session)
        tabs.add(tab)
        setupDelegates(tab)
        switchToSession(tab)
        session.loadUri("https://www.startpage.com/")
    }

    private fun setupDelegates(tab: TabInfo) {
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, 
                url: String?, 
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let {
                    tab.url = it
                    if (tab == activeTab) {
                        runOnUiThread { urlBar.setText(it) }
                    }
                }
            }
        }
        tab.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let { tab.title = it }
            }
        }
    }

    private fun switchToSession(tab: TabInfo) {
        for (t in tabs) {
            t.session.setActive(t == tab) 
        }

        if (geckoView.session != tab.session) {
            geckoView.setSession(tab.session)
        }
        activeTab = tab
        urlBar.setText(tab.url)
        tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
    }

    private fun closeSession(tab: TabInfo) {
        tab.session.close()
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            createNewSession()
        } else {
            switchToSession(tabs.last())
        }
    }

    private fun openTabManager() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_tabs, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_tabs)
        
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = TabAdapter(
            tabs, 
            activeTab,
            onClick = { selectedTab ->
                switchToSession(selectedTab)
                bottomSheet.dismiss()
            },
            onClose = { tabToClose ->
                closeSession(tabToClose)
                bottomSheet.dismiss()
                openTabManager() 
            }
        )

        view.findViewById<ImageButton>(R.id.btn_new_tab).setOnClickListener {
            createNewSession()
            bottomSheet.dismiss()
        }

        bottomSheet.setContentView(view)
        bottomSheet.show()
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
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        stopService(serviceIntent)
    }

    override fun onStop() {
        super.onStop()
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        startForegroundService(serviceIntent)
    }

    override fun onPause() {
        super.onPause()
        if (::activeTab.isInitialized) {
            activeTab.session.setActive(false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::activeTab.isInitialized) {
            activeTab.session.setActive(true)
        }
    }
}
