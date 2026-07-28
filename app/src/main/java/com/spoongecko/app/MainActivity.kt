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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.regex.Pattern

// Wrapper to hold session state and UI titles
data class TabInfo(val session: GeckoSession, var title: String = "New Tab", var url: String = "")

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var runtime: GeckoRuntime
    private lateinit var urlBar: EditText
    
    private val tabs = mutableListOf<TabInfo>()
    private lateinit var activeTab: TabInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        urlBar = findViewById(R.id.url_bar)

        requestBatteryExemption()

        if (!::runtime.isInitialized) {
            val runtimeSettings = GeckoRuntimeSettings.Builder().build()
            runtime = GeckoRuntime.create(this, runtimeSettings)
        }

        setupUIListeners()
        createNewSession() // Start with one tab
    }

    private fun setupUIListeners() {
        // URL Bar Search/Load
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
            Toast.makeText(this, "Settings coming in Phase 5", Toast.LENGTH_SHORT).show() 
        }
    }

    private fun loadUrlOrSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        
        // Simple regex to check if it's a URL or a search query
        val urlPattern = Pattern.compile("^[a-zA-Z0-9\\-\\.] +\\.[a-zA-Z]{2,}$")
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
        val session = GeckoSession()
        session.open(runtime)
        val tab = TabInfo(session)
        tabs.add(tab)
        setupDelegates(tab)
        switchToSession(tab)
        session.loadUri("https://www.startpage.com/")
    }

    private fun setupDelegates(tab: TabInfo) {
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            // The signature changed in GeckoView v125+ to include permissions and user gesture tracking
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
                openTabManager() // Refresh the sheet
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
}
