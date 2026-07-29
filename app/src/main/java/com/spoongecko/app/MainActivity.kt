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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.regex.Pattern

data class TabInfo(val session: GeckoSession, var title: String = "New Tab", var url: String = "")

class MainActivity : AppCompatActivity() {

    // Singleton to prevent "Only one GeckoRuntime instance is allowed" crash on Activity recreation
    companion object {
        private var geckoRuntime: GeckoRuntime? = null
    }

    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var mainLayout: ConstraintLayout
    
    private val tabs = mutableListOf<TabInfo>()
    private lateinit var activeTab: TabInfo

    // Safely retrieves or creates the global runtime instance exactly once
    private val runtime: GeckoRuntime
        get() {
            if (geckoRuntime == null) {
                // We use applicationContext to prevent memory leaks
                geckoRuntime = GeckoRuntime.create(applicationContext, GeckoRuntimeSettings.Builder().build())
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
        applyMenuPosition() // Apply saved menu position on startup
        createNewSession()
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
            if (!isCurrentlyBottom) "✓ Menu at Top" else "Move Menu to Top"
        )

        AlertDialog.Builder(this)
            .setTitle("Toolbar Position")
            .setItems(options) { _, which ->
                val newValue = when (which) {
                    0 -> true  // Bottom
                    1 -> false // Top
                    else -> true
                }
                if (newValue != isCurrentlyBottom) {
                    prefs.edit().putBoolean("menu_at_bottom", newValue).apply()
                    applyMenuPosition()
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
        // Engine-Level Optimizations
        val settings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true) // Blocks ads/trackers at the engine level (Massive speed boost)
            .useMultiprocess(true)       // Isolates tabs so one crash doesn't kill the whole app
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
        // FREEZE MECHANISM: Iterate through all tabs and suspend the inactive ones
        for (t in tabs) {
            // setActive(false) stops JS timers, CSS animations, and network requests for background tabs
            t.session.setActive(t == tab) 
        }

        if (geckoView.session != tab.session) {
            geckoView.setSession(tab.session)
        }
        activeTab = tab
        urlBar.setText(tab.url)
        
        // Ensure the active tab has high priority for the OS
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

    override fun onPause() {
        super.onPause()
        // When the app is minimized or the screen turns off, freeze the active tab to save battery
        if (::activeTab.isInitialized) {
            activeTab.session.setActive(false)
        }
    }

    override fun onResume() {
        super.onResume()
        // Wake the tab back up when the user returns to the app
        if (::activeTab.isInitialized) {
            activeTab.session.setActive(true)
        }
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
}
