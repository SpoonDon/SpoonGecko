package com.spoongecko.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: AutoCompleteTextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var vaultManager: SecureCredentialManager
    private lateinit var tabManager: TabManager
    private lateinit var sessionAttacher: SessionDelegateAttacher
    private lateinit var gestureManager: GestureManager
    private lateinit var extensionManager: ExtensionManager
    private lateinit var menus: BrowserMenusHelper
    private val runtime by lazy { GeckoRuntimeManager.getRuntime(applicationContext) }
    private var isFullScreen = false
    private val lastExtensionUpdateTime = AtomicReference<Long>(0L)

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vaultManager.exportToCsv(it, this) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vaultManager.importFromCsv(it, this) }
    }

    private val installExtensionPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { extensionManager.installFromFile(it) }
    }
    private val backupExtensionsPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { extensionManager.backupToFile(it) }
    }
    private val restoreExtensionsPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { extensionManager.restoreFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)
        vaultManager = SecureCredentialManager(this)
        geckoView = findViewById(R.id.gecko_view)
        urlBar = findViewById(R.id.url_bar)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        geckoView.coverUntilFirstPaint(Color.parseColor("#121212"))

        initManagers()
        setupUIListeners()
        setupSystemBackButton()
        requestNotificationPermission()
        requestBatteryExemption()

        val initialUrl = intent?.dataString
        tabManager.createNewSession()
        if (!initialUrl.isNullOrEmpty()) {
            tabManager.activeTab?.session?.loadUri(initialUrl)
        }
    }

    private fun initManagers() {
        tabManager = TabManager(
            runtime = runtime,
            geckoView = geckoView,
            onActiveTabChanged = { tab -> onActiveTabChanged(tab) },
            onLastTabClosed = { showExitConfirmation() },
            onSessionCreated = { tab -> sessionAttacher.attach(tab) }
        )

        sessionAttacher = SessionDelegateAttacher(
            activity = this,
            runtime = runtime,
            dbHelper = dbHelper,
            vaultManager = vaultManager,
            onTabStateChanged = { tab -> onTabStateChanged(tab) },
            onFullScreenRequested = { fullScreen -> setFullScreen(fullScreen) }
        )

        gestureManager = GestureManager(
            context = this,
            geckoView = geckoView,
            getActiveTab = { tabManager.activeTab },
            onSwipeCloseTab = { tabManager.activeTab?.let { tabManager.closeSession(it) } }
        )
        gestureManager.attach()

        extensionManager = ExtensionManager(runtime, this)
        extensionManager.setupDelegates()

        menus = BrowserMenusHelper(
            activity = this,
            dbHelper = dbHelper,
            tabManager = tabManager,
            vaultManager = vaultManager,
            extensionManager = extensionManager,
            onNavigate = { url -> tabManager.activeTab?.session?.loadUri(url) },
            onExitRequested = { exitApp() },
            onInstallExtensionFromFile = { installExtensionPicker.launch("*/*") },
            onBackupExtensions = { backupExtensionsPicker.launch("extensions-backup.json") },
            onRestoreExtensions = { restoreExtensionsPicker.launch("*/*") },
            onExportCsv = { exportLauncher.launch("vault-export.csv") },
            onImportCsv = { importLauncher.launch("*/*") }
        )

        // Issue #5: Replace Thread.start() with bounded executor
        runtime.setAutocompleteStorageDelegate(object : Autocomplete.StorageDelegate {
            override fun onLoginSave(login: Autocomplete.LoginEntry) {
                val origin = login.origin ?: return
                val username = login.username ?: return
                val password = login.password ?: return
                
                BackgroundExecutor.execute {
                    vaultManager.saveCredentials(origin, username, password)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "🔐 Password saved for ${login.origin}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            
            override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? {
                val result = GeckoResult<Array<Autocomplete.LoginEntry>>()
                BackgroundExecutor.execute {
                    val logins = vaultManager.getLoginsForDomain(domain)
                    result.complete(logins.toTypedArray())
                }
                return result
            }
        })
    }

    private fun onTabStateChanged(tab: TabInfo) {
        if (tab == tabManager.activeTab) {
            runOnUiThread {
                if (!urlBar.hasFocus()) {
                    urlBar.setText(if (tab.url == "about:blank" || tab.url.startsWith("javascript:")) "" else tab.url)
                }
                updateNavButtons()
            }
        }
    }

    private fun onActiveTabChanged(tab: TabInfo?) {
        if (isFullScreen) setFullScreen(false)
        runOnUiThread {
            urlBar.setText(if (tab?.url == "about:blank") "" else (tab?.url ?: ""))
            updateNavButtons()
        }
    }

    private fun setupUIListeners() {
        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                tabManager.activeTab?.let { UrlRouter.loadUrlOrSearch(v.text.toString(), it.session, this) }
                urlBar.clearFocus()
                urlBar.dismissDropDown()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
                true
            } else {
                false
            }
        }

        urlBar.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) v.post { (v as AutoCompleteTextView).selectAll() }
        }

        btnBack.setOnClickListener { handleBackNavigation() }
        btnForward.setOnClickListener { tabManager.activeTab?.let { if (it.canGoForward) it.session.goForward() } }
        findViewById<ImageButton>(R.id.btn_reload).setOnClickListener { tabManager.activeTab?.session?.reload() }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { menus.openTabManager() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { menus.showMenuOptions(it) }

        setupSuggestions()
    }

    private fun setupSuggestions() {
        val suggestionList = mutableListOf<String>()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, suggestionList) {
            override fun getFilter(): Filter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val query = constraint?.toString()?.trim() ?: ""
                    val found = if (query.isEmpty()) emptyList() else dbHelper.getSuggestions(query)
                    return FilterResults().apply {
                        values = found
                        count = found.size
                    }
                }
                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    suggestionList.clear()
                    (results?.values as? List<String>)?.let { suggestionList.addAll(it) }
                    if (suggestionList.isNotEmpty()) notifyDataSetChanged() else notifyDataSetInvalidated()
                }
            }
        }
        urlBar.setAdapter(adapter)
        urlBar.threshold = 1

        var suppress = false
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppress) { suppress = false; return }
                if (urlBar.hasFocus()) adapter.filter.filter(s)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        urlBar.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            suppress = true
            tabManager.activeTab?.let { UrlRouter.loadUrlOrSearch(selected, it.session, this) }
            urlBar.setText(selected)
            urlBar.clearFocus()
            urlBar.dismissDropDown()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
        }
    }

    private fun handleBackNavigation() {
        if (isFullScreen) {
            tabManager.activeTab?.session?.loadUri("javascript:(function(){ if(document.exitFullscreen) document.exitFullscreen(); else if(document.webkitExitFullscreen) document.webkitExitFullsc[...]
            return
        }
        val tab = tabManager.activeTab
        if (tab == null) {
            exitApp()
            return
        }
        if (tab.canGoBack) {
            tab.session.goBack()
        } else if (tabManager.tabs.size > 1) {
            tabManager.closeSession(tab)
        } else {
            showExitConfirmation()
        }
    }

    private fun updateNavButtons() {
        val tab = tabManager.activeTab
        btnBack.alpha = if (tab != null && tab.canGoBack) 1.0f else 0.5f
        btnForward.alpha = if (tab != null && tab.canGoForward) 1.0f else 0.5f
        btnBack.isEnabled = tab != null && tab.canGoBack
        btnForward.isEnabled = tab != null && tab.canGoForward
    }

    private fun setFullScreen(fullScreen: Boolean) {
        isFullScreen = fullScreen
        val topBar = findViewById<android.view.View>(R.id.top_bar)
        if (fullScreen) {
            topBar.visibility = android.view.View.GONE
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            topBar.visibility = android.view.View.VISIBLE
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        }
    }

    private fun setupSystemBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            } catch (_: Exception) {}
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
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

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, KeepAliveService::class.java))
    }

    override fun onStop() {
        super.onStop()
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // Issue #8: Throttle extension updates - max once per 5 minutes
        val now = System.currentTimeMillis()
        val lastUpdate = lastExtensionUpdateTime.get()
        if (now - lastUpdate > 5 * 60 * 1000) {
            lastExtensionUpdateTime.set(now)
            BackgroundExecutor.execute {
                extensionManager.updateAll()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            GeckoRuntimeManager.shutdown()
            BackgroundExecutor.shutdown()
        }
    }
}
