package com.spoongecko.app

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
import androidx.activity.result.contract.ActivityResultContracts
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

// ============================================================================
// DATA MODEL
// ============================================================================
data class TabInfo(
    val session: GeckoSession,
    var title: String = "New Tab",
    var url: String = "",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false
)

// ============================================================================
// MAIN ACTIVITY
// ============================================================================
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
    
    private var pendingExportData: String = ""
    
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(pendingExportData.toByteArray())
                }
                Toast.makeText(this, "Vault exported.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { stream ->
                    val csv = stream.bufferedReader().readText()
                    importCsvData(csv)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingFilePrompt != null && pendingFileResult != null) {
            try {
                // Grant read permission so GeckoView can read the selected file
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { /* Ignore if not persistable */ }
            pendingFileResult?.complete(pendingFilePrompt!!.confirm(this, uri))
        } else {
            pendingFileResult?.complete(pendingFilePrompt?.dismiss())
        }
        pendingFilePrompt = null
        pendingFileResult = null
    }

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

    override fun onStart() { super.onStart(); stopService(Intent(this, KeepAliveService::class.java)) }
    override fun onStop() { super.onStop(); startForegroundService(Intent(this, KeepAliveService::class.java)) }
    override fun onPause() { super.onPause() }
    override fun onResume() { super.onResume(); extensionManager.checkForUpdates() }
    
    @Suppress("KotlinConstantConditions")
    override fun onDestroy() { 
        super.onDestroy()
        if (isFinishing) {
            try {
                val flags = org.mozilla.geckoview.StorageController.ClearFlags.NETWORK_CACHE
                runtime.storageController.clearData(flags)
            } catch (e: Exception) {}
            GeckoRuntimeManager.shutdown()
        }
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
            if (hasFocus) {
                v.post { (v as EditText).selectAll() }
            }
        }
        btnBack.setOnClickListener { handleBackNavigation() }
        btnForward.setOnClickListener { if (::activeTab.isInitialized && activeTab.canGoForward) activeTab.session.goForward() }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { openTabManager() }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenuOptions() }
    }

    private fun handleBackNavigation() {
        if (!::activeTab.isInitialized) { exitApp(); return }
        if (activeTab.canGoBack) activeTab.session.goBack()
        else if (tabs.size > 1) closeSession(activeTab)
        else showExitConfirmation()
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
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
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

    private val AUTOSAVE_JS = """
        (function() {
            if (window.__spoonVaultInjected) return;
            window.__spoonVaultInjected = true;
            function monitorForm(form) {
                form.addEventListener('submit', function() {
                    try {
                        var passField = form.querySelector('input[type="password"]');
                        if (!passField || !passField.value) return;
                        var userField = form.querySelector('input[type="email"], input[type="text"], input[name*="user"], input[name*="email"], input[name*="login"], input[autocomplete="username"]');
                        var username = userField ? userField.value : '';
                        var password = passField.value;
                        if (password.length > 0) {
                            var host = window.location.hostname.replace(/^www\./, '');
                            var msg = 'SPOON_VAULT_SAVE:' + JSON.stringify({host:host, user:username, pass:password});
                            var originalTitle = document.title;
                            document.title = msg;
                            setTimeout(function() { document.title = originalTitle; }, 100);
                        }
                    } catch(e) {}
                });
            }
            document.querySelectorAll('form').forEach(monitorForm);
            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(m) {
                    m.addedNodes.forEach(function(node) {
                        if (node.tagName === 'FORM') monitorForm(node);
                        if (node.querySelectorAll) node.querySelectorAll('form').forEach(monitorForm);
                    });
                });
            });
            observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()

    private fun setupDelegates(tab: TabInfo) {
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (uri.endsWith(".xpi", ignoreCase = true) || (uri.contains("addons.mozilla.org") && uri.contains("/downloads/"))) {
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
                    if (tab == activeTab) runOnUiThread { urlBar.setText(if (it == "about:blank") "" else it) }
                    if (it != "about:blank" && !it.startsWith("data:") && !it.startsWith("moz-extension:") && !it.startsWith("spoonvault://")) {
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
        
        tab.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    session.loadUri("javascript:$AUTOSAVE_JS")
                }
            }
        }

        tab.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let {
                    if (it.startsWith("SPOON_VAULT_SAVE:")) {
                        handleAutoSaveTitle(it)
                    } else {
                        tab.title = if (it.startsWith("data:") || it == "about:blank" || it.isEmpty()) "New Tab" else it
                    }
                }
            }
        }

        tab.session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFilePrompt = prompt
                pendingFileResult = result
                
                val mimeTypes = prompt.mimeTypes?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
                try {
                    filePickerLauncher.launch(mimeTypes)
                } catch (e: Exception) {
                    filePickerLauncher.launch(arrayOf("*/*"))
                }
                return result
            }

            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title ?: "Message")
                        .setMessage(prompt.message ?: "")
                        .setPositiveButton("OK") { _, _ -> result.complete(prompt.confirm()) }
                        .setOnDismissListener { result.complete(prompt.dismiss()) }
                        .show()
                }
                return result
            }

            override fun onConfirmPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ConfirmPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title ?: "Confirm")
                        .setMessage(prompt.message ?: "")
                        .setPositiveButton("OK") { _, _ -> result.complete(prompt.confirm(true)) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(prompt.confirm(false)) }
                        .setOnDismissListener { result.complete(prompt.dismiss()) }
                        .show()
                }
                return result
            }
            
            override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val input = EditText(this@MainActivity).apply {
                    setText(prompt.defaultValue ?: "")
                }
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title ?: "Input")
                        .setMessage(prompt.message ?: "")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(prompt.dismiss()) }
                        .setOnDismissListener { result.complete(prompt.dismiss()) }
                        .show()
                }
                return result
            }
        }
    }

    private fun handleAutoSaveTitle(title: String) {
        try {
            val jsonStr = title.removePrefix("SPOON_VAULT_SAVE:")
            val parsed = org.json.JSONObject(jsonStr)
            val host = parsed.optString("host") ?: return
            val user = parsed.optString("user") ?: ""
            val pass = parsed.optString("pass") ?: return
            if (pass.isEmpty()) return
            
            val ignored = getSharedPreferences("vault_ignored", Context.MODE_PRIVATE).getBoolean(host, false)
            if (ignored) return
            
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Save Credentials?")
                    .setMessage("Save login for $host?\n\nUsername: ${user.ifEmpty { "(empty)" }}")
                    .setPositiveButton("Save") { _, _ -> vaultManager.saveCredentials(host, user, pass); Toast.makeText(this, "Saved to vault.", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Not Now", null)
                    .setNeutralButton("Never") { _, _ -> getSharedPreferences("vault_ignored", Context.MODE_PRIVATE).edit().putBoolean(host, true).apply() }
                    .show()
            }
        } catch (e: Exception) { }
    }

    private fun showMenuOptions() {
        val normal = { text: String -> SpannableString(text) as CharSequence }
        val redText = SpannableString("Exit App")
        redText.setSpan(ForegroundColorSpan(Color.RED), 0, redText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val options = arrayOf<CharSequence>(
            normal("Reload Page"),
            normal("Copy Site ID"),
            normal("Copy Site Password"),
            normal("History"), 
            normal("Bookmarks"), 
            normal("Vault Manager"), 
            normal("Extensions"), 
            normal("Clear Browsing Data"), 
            redText
        )
        
        AlertDialog.Builder(this).setTitle("Menu").setItems(options) { _, which ->
            when (which) {
                0 -> if (::activeTab.isInitialized) activeTab.session.reload()
                1 -> quickCopyVaultId()
                2 -> quickCopyVaultPassword()
                3 -> openHistoryManager()
                4 -> openBookmarkManager()
                5 -> openVaultManager()
                6 -> showExtensionsMenu()
                7 -> clearBrowsingData()
                8 -> exitApp()
            }
        }.show()
    }

    private fun quickCopyVaultId() {
        if (!::activeTab.isInitialized || activeTab.url.isEmpty() || activeTab.url == "about:blank") { Toast.makeText(this, "No valid page loaded.", Toast.LENGTH_SHORT).show(); return }
        val host = Uri.parse(activeTab.url).host?.lowercase()?.trim()?.removePrefix("www.") ?: return
        val username = vaultManager.getUsername(host)
        if (username.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("username", username))
            Toast.makeText(this, "ID Copied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No ID saved for $host", Toast.LENGTH_SHORT).show()
        }
    }

    private fun quickCopyVaultPassword() {
        if (!::activeTab.isInitialized || activeTab.url.isEmpty() || activeTab.url == "about:blank") { Toast.makeText(this, "No valid page loaded.", Toast.LENGTH_SHORT).show(); return }
        val host = Uri.parse(activeTab.url).host?.lowercase()?.trim()?.removePrefix("www.") ?: return
        val password = vaultManager.getPassword(host)
        if (password.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", password))
            Toast.makeText(this, "Password Copied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No password saved for $host", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this).setTitle("Clear Browsing Data?").setMessage("This will clear cache, cookies, and history.")
            .setPositiveButton("Clear") { _, _ ->
                val flags = org.mozilla.geckoview.StorageController.ClearFlags.ALL
                runtime.storageController.clearData(flags).accept {
                    runOnUiThread {
                        dbHelper.deleteAllHistory()
                        Toast.makeText(this, "Data cleared.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this).setTitle("Exit Spoon Gecko?").setMessage("Are you sure you want to close the browser?")
            .setPositiveButton("Exit") { _, _ -> exitApp() }.setNegativeButton("Cancel", null).show()
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
                    val s = GeckoSession(GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP).suspendMediaWhenInactive(true).build())
                    s.open(runtime); val tab = TabInfo(s); tabs.add(tab); setupDelegates(tab); switchToSession(tab)
                    s.loadUri("https://addons.mozilla.org/firefox/")
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
                    val names = extensions.map { it.metaData.name ?: "Unknown" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Manage Extensions").setItems(names) { _, w -> showExtensionActions(extensions[w]) }.setNegativeButton("Close", null).show()
                }
            },
            { t -> runOnUiThread { Toast.makeText(this, "Failed: ${t?.message}", Toast.LENGTH_SHORT).show() } }
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
                "Uninstall" -> runtime.webExtensionController.uninstall(extension).accept(
                    { runOnUiThread { Toast.makeText(this, "Uninstalled.", Toast.LENGTH_SHORT).show() } },
                    { t -> runOnUiThread { Toast.makeText(this, "Failed: ${t?.message}", Toast.LENGTH_SHORT).show() } }
                )
            }
        }.show()
    }

    private fun openExtensionPopup(extension: WebExtension) {
        val baseUrl = extension.metaData.baseUrl ?: run { Toast.makeText(this, "No popup available.", Toast.LENGTH_SHORT).show(); return }
        val popupSession = GeckoSession()
        popupSession.open(runtime)
        val popupView = org.mozilla.geckoview.GeckoView(this)
        popupView.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.65).toInt())
        popupView.setSession(popupSession)
        popupSession.loadUri("${baseUrl}popup/index.html")
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
        fun refresh(search: String = "", sortIdx: Int = 0) {
            val entries = dbHelper.getHistory(search, sortValues[sortIdx])
            recycler.adapter = HistoryAdapter(entries,
                onClick = { e -> createNewSession(); activeTab.session.loadUri(e.url); bottomSheet.dismiss() },
                onStar = { e -> if (dbHelper.addBookmark(e.url, e.title)) Toast.makeText(this, "Bookmarked!", Toast.LENGTH_SHORT).show() else Toast.makeText(this, "Already bookmarked.", Toast.LENGTH_SHORT).show() },
                onDelete = { e -> dbHelper.deleteHistory(e.id); refresh(searchBox.text.toString(), sortSpinner.selectedItemPosition) }
            )
        }
        refresh()
        searchBox.setOnEditorActionListener { v, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { refresh(v.text.toString(), sortSpinner.selectedItemPosition); true } else false }
        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { refresh(searchBox.text.toString(), pos) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        view.findViewById<ImageButton>(R.id.btn_delete_all_history).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Delete All History?").setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> dbHelper.deleteAllHistory(); refresh(); Toast.makeText(this, "Cleared.", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
        bottomSheet.setContentView(view); bottomSheet.show()
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
        fun refresh(sortIdx: Int = 0) {
            val entries = dbHelper.getBookmarks(sortValues[sortIdx])
            recycler.adapter = BookmarkAdapter(entries,
                onClick = { e -> createNewSession(); activeTab.session.loadUri(e.url); bottomSheet.dismiss() },
                onEdit = { e -> showEditBookmarkDialog(e) { refresh(sortSpinner.selectedItemPosition) } },
                onDelete = { e -> dbHelper.deleteBookmark(e.id); refresh(sortSpinner.selectedItemPosition) }
            )
        }
        refresh()
        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { refresh(pos) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        view.findViewById<ImageButton>(R.id.btn_add_bookmark).setOnClickListener {
            if (::activeTab.isInitialized && activeTab.url.isNotEmpty() && activeTab.url != "about:blank") {
                if (dbHelper.addBookmark(activeTab.url, activeTab.title)) { Toast.makeText(this, "Bookmarked!", Toast.LENGTH_SHORT).show(); refresh(sortSpinner.selectedItemPosition) }
                else Toast.makeText(this, "Already bookmarked.", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "No page to bookmark.", Toast.LENGTH_SHORT).show()
        }
        bottomSheet.setContentView(view); bottomSheet.show()
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
        val options = arrayOf("Copy for Current Site", "Save Current Page Credentials", "Manage All Credentials", "Export Vault (CSV)", "Import Vault (CSV)")
        AlertDialog.Builder(this).setTitle("Vault").setItems(options) { _, which ->
            when (which) {
                0 -> showVaultForCurrentSite()
                1 -> showSaveCredentialDialog()
                2 -> openVaultManager()
                3 -> exportVaultCsv()
                4 -> importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
            }
        }.show()
    }

    private fun showVaultForCurrentSite() {
        if (!::activeTab.isInitialized || activeTab.url.isEmpty() || activeTab.url == "about:blank") { Toast.makeText(this, "No valid page loaded.", Toast.LENGTH_SHORT).show(); return }
        val host = Uri.parse(activeTab.url).host?.lowercase()?.trim()?.removePrefix("www.") ?: return
        val json = vaultManager.getAllAccountsForHost(host)
        if (json == "[]") { Toast.makeText(this, "No saved passwords for $host", Toast.LENGTH_SHORT).show(); return }
        try {
            val arr = org.json.JSONArray(json)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
            layout.addView(android.widget.TextView(this).apply { text = "Vault: $host"; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 24) })
            for (i in 0 until arr.length()) {
                val acc = arr.getJSONObject(i)
                val user = acc.optString("username", "")
                val pass = acc.optString("password", "")
                layout.addView(android.widget.Button(this).apply { text = "Copy ID: $user"; isAllCaps = false; setOnClickListener { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("username", user)); Toast.makeText(this@MainActivity, "ID Copied", Toast.LENGTH_SHORT).show() } })
                layout.addView(android.widget.Button(this).apply { text = "Copy Password"; isAllCaps = false; setOnClickListener { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", pass)); Toast.makeText(this@MainActivity, "Password Copied", Toast.LENGTH_SHORT).show() } })
            }
            AlertDialog.Builder(this).setView(layout).setNegativeButton("Close", null).show()
        } catch (e: Exception) { Toast.makeText(this, "Vault error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showSaveCredentialDialog() {
        if (!::activeTab.isInitialized || activeTab.url.isEmpty() || activeTab.url == "about:blank") { Toast.makeText(this, "No valid page loaded.", Toast.LENGTH_SHORT).show(); return }
        val host = Uri.parse(activeTab.url).host?.lowercase()?.trim()?.removePrefix("www.") ?: return
        val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val userInput = EditText(this).apply { hint = "Username / Email" }
        val passInput = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(userInput); layout.addView(passInput)
        AlertDialog.Builder(this).setTitle("Save Credentials\n$host").setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val u = userInput.text.toString().trim(); val p = passInput.text.toString()
                if (u.isNotEmpty() && p.isNotEmpty()) { vaultManager.saveCredentials(host, u, p); Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "Both fields required.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openVaultManager() {
        val json = vaultManager.getAllCredentialsAsJson()
        try {
            val arr = org.json.JSONArray(json)
            if (arr.length() == 0) { Toast.makeText(this, "Vault is empty.", Toast.LENGTH_LONG).show(); return }
            val display = mutableListOf<String>()
            for (i in 0 until arr.length()) { val e = arr.getJSONObject(i); display.add("${e.optString("host", "?")}  •  ${e.optString("username", "?")}") }
            AlertDialog.Builder(this).setTitle("Saved Credentials (${arr.length()})")
                .setItems(display.toTypedArray()) { _, w -> showCredentialActions(arr.getJSONObject(w)) }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) { Toast.makeText(this, "Vault error: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showCredentialActions(entry: org.json.JSONObject) {
        val host = entry.optString("host", ""); val username = entry.optString("username", ""); val password = entry.optString("password", "")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val options = arrayOf("Copy Username", "Copy Password", "Edit Password", "Delete")
        AlertDialog.Builder(this).setTitle("$host\n$username").setItems(options) { _, which ->
            when (which) {
                0 -> { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("username", username)); Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show() }
                1 -> { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", password)); Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show() }
                2 -> showEditPasswordDialog(host, username)
                3 -> AlertDialog.Builder(this).setTitle("Delete?").setMessage("$host • $username")
                    .setPositiveButton("Delete") { _, _ -> vaultManager.deleteCredentials(host, username); Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun showEditPasswordDialog(host: String, username: String) {
        val input = EditText(this).apply { hint = "New password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(48, 32, 48, 16) }
        AlertDialog.Builder(this).setTitle("Edit Password\n$host • $username").setView(input)
            .setPositiveButton("Save") { _, _ -> val p = input.text.toString(); if (p.isNotEmpty()) { vaultManager.editCredentialPassword(host, username, p); Toast.makeText(this, "Updated.", Toast.LENGTH_SHORT).show() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportVaultCsv() {
        val json = vaultManager.getAllCredentialsAsJson()
        try {
            val arr = org.json.JSONArray(json)
            if (arr.length() == 0) { Toast.makeText(this, "Vault is empty.", Toast.LENGTH_SHORT).show(); return }
            val sb = StringBuilder("host,username,password\n")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                sb.append("${escapeCsv(e.optString("host", ""))},${escapeCsv(e.optString("username", ""))},${escapeCsv(e.optString("password", ""))}\n")
            }
            pendingExportData = sb.toString()
            exportLauncher.launch("spoon_gecko_vault_${System.currentTimeMillis()}.csv")
        } catch (e: Exception) { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun importCsvData(csv: String) {
        try {
            val lines = csv.split("\n").filter { it.isNotBlank() }
            var imported = 0; var skipped = 0
            for ((index, line) in lines.withIndex()) {
                if (index == 0 && line.lowercase().contains("host") && line.lowercase().contains("password")) continue
                val parts = parseCsvLine(line)
                if (parts.size >= 3) {
                    val h = parts[0].trim(); val u = parts[1].trim(); val p = parts[2].trim()
                    if (h.isNotEmpty() && p.isNotEmpty()) { vaultManager.saveCredentials(h, u, p); imported++ } else skipped++
                } else skipped++
            }
            runOnUiThread { Toast.makeText(this, "Imported: $imported | Skipped: $skipped", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show() } }
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>(); var current = StringBuilder(); var inQuotes = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> { if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ } else inQuotes = !inQuotes }
                c == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
