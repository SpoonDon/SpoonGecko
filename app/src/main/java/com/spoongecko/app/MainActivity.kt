package com.spoongecko.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
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
import org.mozilla.geckoview.WebExtension
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
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private lateinit var extensionManager: ExtensionManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var vaultManager: SecureCredentialManager

    private val tabs = mutableListOf<TabInfo>()
    private lateinit var activeTab: TabInfo

    private val runtime by lazy { GeckoRuntimeManager.getRuntime(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)
        vaultManager = SecureCredentialManager(this)
        extensionManager = ExtensionManager(runtime, this)
        extensionManager.setupDelegates()

        geckoView = findViewById(R.id.gecko_view)
        urlBar = findViewById(R.id.url_bar)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)

        geckoView.isVerticalScrollBarEnabled = false
        geckoView.isHorizontalScrollBarEnabled = false
        geckoView.coverUntilFirstPaint(Color.parseColor("#121212"))

        requestBatteryExemption()
        setupUIListeners()
        setupSystemBackButton()
        createNewSession()
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, KeepAliveService::class.java))
    }

    override fun onStop() {
        super.onStop()
        startForegroundService(Intent(this, KeepAliveService::class.java))
    }

    override fun onPause() { super.onPause() }

    override fun onResume() {
        super.onResume()
        extensionManager.checkForUpdates()
    }

    @Suppress("KotlinConstantConditions")
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) GeckoRuntimeManager.shutdown()
    }

    private fun setupSystemBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackNavigation() }
        })
    }

    private fun setupUIListeners() {
        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadUrlOrSearch(v.text.toString()); true
            } else false
        }
        urlBar.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) (v as EditText).selectAll()
        }
        btnBack.setOnClickListener { handleBackNavigation() }
        btnForward.setOnClickListener {
            if (::activeTab.isInitialized && activeTab.canGoForward) activeTab.session.goForward()
        }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { openTabManager() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenuOptions() }
    }

    private fun handleBackNavigation() {
        if (!::activeTab.isInitialized) { exitApp(); return }
        if (activeTab.canGoBack) {
            activeTab.session.goBack()
        } else if (tabs.size > 1) {
            closeSession(activeTab)
        } else {
            showExitConfirmation()
        }
    }

    private fun loadUrlOrSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        val ipv4Pattern = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$")
        val isIp = ipv4Pattern.matcher(trimmed).matches()
        val domainPattern = Pattern.compile("^[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,}$")
        val isDomain = domainPattern.matcher(trimmed).matches()
        val isLocalhost = trimmed.equals("localhost", ignoreCase = true)
        val isUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") || isIp || isDomain || isLocalhost

        if (isUrl) {
            val finalUrl = when {
                trimmed.startsWith("http") -> trimmed
                isIp || isLocalhost -> "http://$trimmed"
                else -> "https://$trimmed"
            }
            activeTab.session.loadUri(finalUrl)
        } else {
            activeTab.session.loadUri("https://search.brave.com/search?q=$trimmed")
        }

        urlBar.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (::activeTab.isInitialized && activeTab.canGoBack) 1.0f else 0.5f
        btnForward.alpha = if (::activeTab.isInitialized && activeTab.canGoForward) 1.0f else 0.5f
        btnBack.isEnabled = ::activeTab.isInitialized && activeTab.canGoBack
        btnForward.isEnabled = ::activeTab.isInitialized && activeTab.canGoForward
    }

    private fun createNewSession() {
        val session = GeckoSession(GeckoSessionSettings.Builder().suspendMediaWhenInactive(true).build())
        session.open(runtime)
        val tab = TabInfo(session)
        tabs.add(tab)
        setupDelegates(tab)
        switchToSession(tab)
        session.loadUri("about:blank")
    }

    private fun switchToSession(tab: TabInfo) {
        for (t in tabs) { t.session.setActive(t == tab) }
        if (geckoView.session != tab.session) geckoView.setSession(tab.session)
        activeTab = tab
        urlBar.setText(if (tab.url == "about:blank") "" else tab.url)
        tab.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        updateNavButtons()
    }

    private fun closeSession(tab: TabInfo) {
        tab.session.close()
        tabs.remove(tab)
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
        view.findViewById<ImageButton>(R.id.btn_new_tab).setOnClickListener {
            createNewSession(); bottomSheet.dismiss()
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun setupDelegates(tab: TabInfo) {
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (uri.endsWith(".xpi", ignoreCase = true) ||
                    (uri.contains("addons.mozilla.org") && uri.contains("/downloads/"))) {
                    runtime.webExtensionController.install(uri).accept(
                        { ext -> runOnUiThread { Toast.makeText(this@MainActivity, "Installed: ${ext?.metaData?.name}", Toast.LENGTH_SHORT).show() } },
                        { throwable -> runOnUiThread { Toast.makeText(this@MainActivity, "Install failed: ${throwable?.message}", Toast.LENGTH_SHORT).show() } }
                    )
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                url?.let {
                    tab.url = it
                    if (tab == activeTab) {
                        runOnUiThread { urlBar.setText(if (it == "about:blank") "" else it) }
                    }
                    // Record history for real pages only
                    if (it != "about:blank" && !it.startsWith("data:") && !it.startsWith("moz-extension:")) {
                        Thread { dbHelper.addHistory(it, tab.title) }.start()
                    }
                }
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
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let {
                    tab.title = if (it.startsWith("data:") || it == "about:blank" || it.isEmpty()) "New Tab" else it
                }
            }
        }
    }

    private fun showMenuOptions() {
        val normal = { text: String -> SpannableString(text) as CharSequence }
        val redText = SpannableString("Exit App")
        redText.setSpan(ForegroundColorSpan(Color.RED), 0, redText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val options = arrayOf<CharSequence>(
            normal("History"),
            normal("Bookmarks"),
            normal("Vault"),
            normal("Extensions"),
            normal("Clear Browsing Data"),
            redText
        )

        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openHistoryManager()
                    1 -> openBookmarkManager()
                    2 -> showVaultMenu()
                    3 -> showExtensionsMenu()
                    4 -> Toast.makeText(this, "Coming soon!", Toast.LENGTH_SHORT).show()
                    5 -> exitApp()
                }
            }
            .show()
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

    private fun showExtensionsMenu() {
        val options = arrayOf("Add-ons Store", "Manage Extensions", "Check for Updates")
        AlertDialog.Builder(this).setTitle("Extensions").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val sessionSettings = GeckoSessionSettings.Builder()
                        .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                        .suspendMediaWhenInactive(true).build()
                    val session = GeckoSession(sessionSettings)
                    session.open(runtime)
                    val tab = TabInfo(session)
                    tabs.add(tab); setupDelegates(tab); switchToSession(tab)
                    session.loadUri("https://addons.mozilla.org/firefox/")
                }
                1 -> showManageExtensions()
                2 -> { extensionManager.checkForUpdates(); Toast.makeText(this, "Checking...", Toast.LENGTH_SHORT).show() }
            }
        }.show()
    }

    private fun showManageExtensions() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                runOnUiThread {
                    if (extensions.isNullOrEmpty()) { Toast.makeText(this, "No extensions installed.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    val extNames = extensions.map { it.metaData.name ?: "Unknown" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Manage Extensions").setItems(extNames) { _, which ->
                        showExtensionActions(extensions[which])
                    }.setNegativeButton("Close", null).show()
                }
            },
            { throwable -> runOnUiThread { Toast.makeText(this, "Failed: ${throwable?.message}", Toast.LENGTH_SHORT).show() } }
        )
    }

    private fun showExtensionActions(extension: WebExtension) {
        val options = mutableListOf<String>()
        val baseUrl = extension.metaData.baseUrl

        if (baseUrl != null) options.add("Open Extension Popup")
        if (extension.metaData.optionsPageUrl != null) options.add("Open Settings")
        options.add("Uninstall")

        AlertDialog.Builder(this).setTitle(extension.metaData.name).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Open Extension Popup" -> openExtensionPopup(extension)
                "Open Settings" -> { createNewSession(); activeTab.session.loadUri(extension.metaData.optionsPageUrl!!) }
                "Uninstall" -> {
                    runtime.webExtensionController.uninstall(extension).accept(
                        { runOnUiThread { Toast.makeText(this, "Uninstalled.", Toast.LENGTH_SHORT).show() } },
                        { throwable -> runOnUiThread { Toast.makeText(this, "Failed: ${throwable?.message}", Toast.LENGTH_SHORT).show() } }
                    )
                }
            }
        }.show()
    }

    private fun openExtensionPopup(extension: WebExtension) {
        val baseUrl = extension.metaData.baseUrl
        if (baseUrl == null) { Toast.makeText(this, "No popup available.", Toast.LENGTH_SHORT).show(); return }

        val popupUrl = "${baseUrl}popup/index.html"
        val popupSession = GeckoSession()
        popupSession.open(runtime)

        val popupView = org.mozilla.geckoview.GeckoView(this)
        popupView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.65).toInt()
        )
        popupView.setSession(popupSession)
        popupSession.loadUri(popupUrl)

        val bottomSheet = BottomSheetDialog(this)
        bottomSheet.setContentView(popupView)
        bottomSheet.setOnDismissListener { popupSession.close() }
        bottomSheet.show()
    }

    private fun openHistoryManager() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_history, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        val searchBox = view.findViewById<EditText>(R.id.history_search)
        val sortSpinner = view.findViewById<Spinner>(R.id.history_sort)

        val sortOptions = arrayOf("Newest First", "Oldest First", "Most Visited", "Title A-Z")
        val sortValues = arrayOf("timestamp DESC", "timestamp ASC", "visit_count DESC", "title ASC")
        sortSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sortOptions)
        recycler.layoutManager = LinearLayoutManager(this)

        fun refreshHistory(search: String = "", sortIndex: Int = 0) {
            val entries = dbHelper.getHistory(search, sortValues[sortIndex])
            recycler.adapter = HistoryAdapter(entries,
                onClick = { entry -> createNewSession(); activeTab.session.loadUri(entry.url); bottomSheet.dismiss() },
                onStar = { entry ->
                    if (dbHelper.addBookmark(entry.url, entry.title)) Toast.makeText(this, "Bookmarked!", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, "Already bookmarked.", Toast.LENGTH_SHORT).show()
                },
                onDelete = { entry -> dbHelper.deleteHistory(entry.id); refreshHistory(searchBox.text.toString(), sortSpinner.selectedItemPosition) }
            )
        }

        refreshHistory()
        searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { refreshHistory(v.text.toString(), sortSpinner.selectedItemPosition); true } else false
        }
        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { refreshHistory(searchBox.text.toString(), pos) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        view.findViewById<ImageButton>(R.id.btn_delete_all_history).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Delete All History?").setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> dbHelper.deleteAllHistory(); refreshHistory(); Toast.makeText(this, "History cleared.", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun openBookmarkManager() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_bookmarks, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_bookmarks)
        val sortSpinner = view.findViewById<Spinner>(R.id.bookmark_sort)

        val sortOptions = arrayOf("Newest First", "Oldest First", "Title A-Z")
        val sortValues = arrayOf("timestamp DESC", "timestamp ASC", "title ASC")
        sortSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sortOptions)
        recycler.layoutManager = LinearLayoutManager(this)

        fun refreshBookmarks(sortIndex: Int = 0) {
            val entries = dbHelper.getBookmarks(sortValues[sortIndex])
            recycler.adapter = BookmarkAdapter(entries,
                onClick = { entry -> createNewSession(); activeTab.session.loadUri(entry.url); bottomSheet.dismiss() },
                onEdit = { entry -> showEditBookmarkDialog(entry) { refreshBookmarks(sortSpinner.selectedItemPosition) } },
                onDelete = { entry -> dbHelper.deleteBookmark(entry.id); refreshBookmarks(sortSpinner.selectedItemPosition) }
            )
        }

        refreshBookmarks()
        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { refreshBookmarks(pos) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        view.findViewById<ImageButton>(R.id.btn_add_bookmark).setOnClickListener {
            if (::activeTab.isInitialized && activeTab.url.isNotEmpty() && activeTab.url != "about:blank") {
                if (dbHelper.addBookmark(activeTab.url, activeTab.title)) { Toast.makeText(this, "Bookmarked!", Toast.LENGTH_SHORT).show(); refreshBookmarks(sortSpinner.selectedItemPosition) }
                else Toast.makeText(this, "Already bookmarked.", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "No page to bookmark.", Toast.LENGTH_SHORT).show()
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun showEditBookmarkDialog(entry: BookmarkEntry, onSaved: () -> Unit) {
        val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val titleInput = EditText(this).apply { hint = "Title"; setText(entry.title) }
        val urlInput = EditText(this).apply { hint = "URL"; setText(entry.url) }
        layout.addView(titleInput); layout.addView(urlInput)

        AlertDialog.Builder(this).setTitle("Edit Bookmark").setView(layout)
            .setPositiveButton("Save") { _, _ -> dbHelper.updateBookmark(entry.id, titleInput.text.toString(), urlInput.text.toString()); onSaved() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showVaultMenu() {
        val options = arrayOf("Copy for Current Site", "Save Current Page Credentials", "Manage All Credentials")
        AlertDialog.Builder(this).setTitle("Vault").setItems(options) { _, which ->
            when (which) {
                0 -> showVaultForCurrentSite()
                1 -> showSaveCredentialDialog()
                2 -> openVaultManager()
            }
        }.show()
    }

    private fun showVaultForCurrentSite() {
        if (!::activeTab.isInitialized || activeTab.url.isEmpty() || activeTab.url == "about:blank") {
            Toast.makeText(this, "No valid page loaded.", Toast.LENGTH_SHORT).show(); return
        }
        val host = Uri.parse(activeTab.url).host?.lowercase()?.trim()?.removePrefix("www.") ?: return
        val accountsJson = vaultManager.getAllAccountsForHost(host)
        if (accountsJson == "[]") { Toast.makeText(this, "No saved passwords for $host", Toast.LENGTH_SHORT).show(); return }

        try {
            val accountsArray = org.json.JSONArray(accountsJson)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
            val title = android.widget.TextView(this).apply { text = "Vault: $host"; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 24) }
            layout.addView(title)

            for (i in 0 until accountsArray.length()) {
                val acc = accountsArray.getJSONObject(i)
                val user = acc.optString("username", "")
                val pass = acc.optString("password", "")
                layout.addView(android.widget.Button(this).apply { text = "Copy ID: $user"; isAllCaps = false; setOnClickListener { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("username", user)); Toast.makeText(this@MainActivity, "ID Copied", Toast.LENGTH_SHORT).show() } })
                layout.addView(android.widget.Button(this).apply { text = "Copy Password"; isAllCaps = false; setOnClickListener { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", pass)); Toast.makeText(this@MainActivity, "Password Copied", Toast.LENGTH_SHORT).show() } })
            }
            AlertDialog.Builder(this).setView(layout).setNegativeButton("Close", null).show()
        } catch (e: Exception) { Toast.makeText(this, "Vault error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showSaveCredentialDialog() {
        if (!::activeTab.isInitialized || activeTab.url.isEmpty() || activeTab.url == "about:blank") {
            Toast.makeText(this, "No valid page loaded.", Toast.LENGTH_SHORT).show(); return
        }
        val host = Uri.parse(activeTab.url).host?.lowercase()?.trim()?.removePrefix("www.") ?: return
        val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val userInput = EditText(this).apply { hint = "Username / Email" }
        val passInput = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(userInput); layout.addView(passInput)

        AlertDialog.Builder(this).setTitle("Save Credentials\n$host").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (user.isNotEmpty() && pass.isNotEmpty()) { vaultManager.saveCredentials(host, user, pass); Toast.makeText(this, "Saved to vault.", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "Both fields required.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openVaultManager() {
        val allJson = vaultManager.getAllCredentialsAsJson()
        try {
            val allArray = org.json.JSONArray(allJson)
            if (allArray.length() == 0) { Toast.makeText(this, "Vault is empty.", Toast.LENGTH_LONG).show(); return }

            val displayList = mutableListOf<String>()
            for (i in 0 until allArray.length()) {
                val entry = allArray.getJSONObject(i)
                displayList.add("${entry.optString("host", "?")}  •  ${entry.optString("username", "?")}")
            }

            AlertDialog.Builder(this).setTitle("Saved Credentials (${allArray.length()})")
                .setItems(displayList.toTypedArray()) { _, which -> showCredentialActions(allArray.getJSONObject(which)) }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) { Toast.makeText(this, "Vault error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showCredentialActions(entry: org.json.JSONObject) {
        val host = entry.optString("host", "")
        val username = entry.optString("username", "")
        val password = entry.optString("password", "")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val options = arrayOf("Copy Username", "Copy Password", "Edit Password", "Delete")

        AlertDialog.Builder(this).setTitle("$host\n$username").setItems(options) { _, which ->
            when (which) {
                0 -> { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("username", username)); Toast.makeText(this, "Username copied.", Toast.LENGTH_SHORT).show() }
                1 -> { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", password)); Toast.makeText(this, "Password copied.", Toast.LENGTH_SHORT).show() }
                2 -> showEditPasswordDialog(host, username)
                3 -> {
                    AlertDialog.Builder(this).setTitle("Delete Credential?").setMessage("$host • $username\nThis cannot be undone.")
                        .setPositiveButton("Delete") { _, _ -> vaultManager.deleteCredentials(host, username); Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }.show()
    }

    private fun showEditPasswordDialog(host: String, username: String) {
        val input = EditText(this).apply { hint = "New password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(48, 32, 48, 16) }
        AlertDialog.Builder(this).setTitle("Edit Password\n$host • $username").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newPass = input.text.toString()
                if (newPass.isNotEmpty()) { vaultManager.editCredentialPassword(host, username, newPass); Toast.makeText(this, "Password updated.", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
