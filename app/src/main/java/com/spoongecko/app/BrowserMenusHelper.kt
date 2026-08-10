package com.spoongecko.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class BrowserMenusHelper(
    private val activity: AppCompatActivity,
    private val dbHelper: DatabaseHelper,
    private val tabManager: TabManager,
    private val vaultManager: SecureCredentialManager,
    private val extensionManager: ExtensionManager,
    private val onNavigate: (String) -> Unit,
    private val onExitRequested: () -> Unit,
    private val onInstallExtensionFromFile: () -> Unit,
    private val onBackupExtensions: () -> Unit,
    private val onRestoreExtensions: () -> Unit,
    private val onExportCsv: () -> Unit,
    private val onImportCsv: () -> Unit
) {
    fun showMenuOptions(anchorView: View) {
        val popup = android.widget.PopupMenu(activity, anchorView, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        val exitItem = popup.menu.findItem(R.id.menu_exit)
        if (exitItem != null) {
            val spannableString = android.text.SpannableString(exitItem.title)
            spannableString.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF1744")), 0, exitItem.title.length, 0)
            exitItem.title = spannableString
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> { tabManager.createNewSession(); true }
                R.id.menu_add_bookmark -> {
                    val tab = tabManager.activeTab
                    if (tab != null && tab.url.isNotEmpty()) {
                        dbHelper.addBookmark(tab.url, tab.title)
                        Toast.makeText(activity, "Bookmark added", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.menu_bookmarks -> { showBookmarks(); true }
                R.id.menu_history -> { showHistory(); true }
                R.id.menu_downloads -> { showDownloads(); true }
                R.id.menu_find_in_page -> { showFindInPage(); true }
                R.id.menu_vault_copy -> {
                    val currentUrl = tabManager.activeTab?.url ?: ""
                    if (currentUrl.isEmpty() || currentUrl == "about:blank" || currentUrl.startsWith("data:")) {
                        Toast.makeText(activity, "No active web page", Toast.LENGTH_SHORT).show()
                        return@setOnMenuItemClickListener true
                    }
                    
                    val credentials = vaultManager.getCredentialsForUrl(currentUrl)
                    if (credentials.isEmpty()) {
                        Toast.makeText(activity, "No saved credentials for this site", Toast.LENGTH_SHORT).show()
                        return@setOnMenuItemClickListener true
                    }

                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val titles = credentials.map { "${it.username} (${it.host})" }.toTypedArray()
                    
                    AlertDialog.Builder(activity)
                        .setTitle("Select Account")
                        .setItems(titles) { _, which ->
                            val selected = credentials[which]
                            AlertDialog.Builder(activity)
                                .setTitle(selected.username)
                                .setItems(arrayOf("Copy Username", "Copy Password")) { _, choice ->
                                    val textToCopy = if (choice == 0) selected.username else selected.password
                                    val clip = ClipData.newPlainText("SpoonGecko Credential", textToCopy)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(activity, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                                .show()
                        }
                        .show()
                    true
                }
                R.id.menu_vault -> {
                    val vaultUi = VaultUiHelper(activity, vaultManager, onExportCsv, onImportCsv)
                    vaultUi.showVault()
                    true
                }
                R.id.menu_extensions -> { showExtensions(); true }
                R.id.menu_search_engine -> { showSearchEnginePicker(); true }
                R.id.menu_exit -> { onExitRequested(); true }
                else -> false
            }
        }
        popup.show()
    }

    fun openTabManager() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_tabs, null)
        dialog.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_tabs)
        recyclerView?.layoutManager = LinearLayoutManager(activity)
        
        val currentTab = tabManager.activeTab
        if (currentTab != null) {
            val adapter = TabAdapter(
                tabs = tabManager.tabs,
                activeTab = currentTab,
                onClick = { tab -> tabManager.switchToSession(tab); dialog.dismiss() },
                onClose = { tab -> tabManager.closeSession(tab); dialog.dismiss() }
            )
            recyclerView?.adapter = adapter
        }

        view.findViewById<ImageButton>(R.id.btn_new_tab)?.setOnClickListener { tabManager.createNewSession(); dialog.dismiss() }
        dialog.show()
    }

    private fun showBookmarks() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_bookmarks, null)
        dialog.setContentView(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_bookmarks)
        recycler?.layoutManager = LinearLayoutManager(activity)
        
        fun load() {
            val list = dbHelper.getBookmarks()
            recycler?.adapter = BookmarkAdapter(list, 
                onClick = { onNavigate(it.url); dialog.dismiss() },
                onEdit = { /* Simple edit dialog could go here */ },
                onDelete = { dbHelper.deleteBookmark(it.id); load() }
            )
        }
        load()
        dialog.show()
    }

    private fun showHistory() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_history, null)
        dialog.setContentView(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        val search = view.findViewById<EditText>(R.id.history_search)
        recycler?.layoutManager = LinearLayoutManager(activity)
        
        fun load(query: String = "") {
            val list = dbHelper.getHistory(query)
            recycler?.adapter = HistoryAdapter(list,
                onClick = { onNavigate(it.url); dialog.dismiss() },
                onStar = { dbHelper.addBookmark(it.url, it.title) },
                onDelete = { dbHelper.deleteHistory(it.id); load(query) }
            )
        }
        load()
        
        search?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                load(text)
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
        
        view.findViewById<ImageButton>(R.id.btn_delete_all_history)?.setOnClickListener {
            dbHelper.deleteAllHistory()
            load()
        }
        dialog.show()
    }

    private fun showDownloads() {
        try {
            activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            Toast.makeText(activity, "No download manager app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFindInPage() {
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(R.layout.sheet_find_in_page)
        
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundResource(android.R.color.transparent)
        }

        val input = dialog.findViewById<EditText>(R.id.find_input)
        val btnPrev = dialog.findViewById<TextView>(R.id.find_prev)
        val btnNext = dialog.findViewById<TextView>(R.id.find_next)
        val btnClose = dialog.findViewById<TextView>(R.id.find_close)

        val session = tabManager.activeTab?.session

        btnNext?.setOnClickListener {
            val query = (input?.text ?: "").toString()
            if (query.isNotEmpty()) session?.finder?.find(query, 0)
        }

        btnPrev?.setOnClickListener {
            val query = (input?.text ?: "").toString()
            if (query.isNotEmpty()) session?.finder?.find(query, org.mozilla.geckoview.GeckoSession.FINDER_FIND_BACKWARDS)
        }

        btnClose?.setOnClickListener {
            session?.finder?.clear()
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun showExtensions() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_extensions, null)
        dialog.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_extensions)
        recyclerView?.layoutManager = LinearLayoutManager(activity)

        view.findViewById<TextView>(R.id.ext_install_file)?.setOnClickListener { onInstallExtensionFromFile(); dialog.dismiss() }
        view.findViewById<TextView>(R.id.ext_backup)?.setOnClickListener { onBackupExtensions(); dialog.dismiss() }
        view.findViewById<TextView>(R.id.ext_restore)?.setOnClickListener { onRestoreExtensions(); dialog.dismiss() }

        extensionManager.listExtensions { extensions ->
            activity.runOnUiThread {
                val adapter = ExtensionAdapter(
                    extensions = extensions,
                    onToggle = { ext, enabled -> extensionManager.toggleExtension(ext, enabled) {} },
                    onSettings = { ext -> extensionManager.openSettings(ext) },
                    onUninstall = { ext -> extensionManager.uninstallExtension(ext) {} }
                )
                recyclerView?.adapter = adapter
            }
        }
        dialog.show()
    }

    private fun showSearchEnginePicker() {
        val engines = arrayOf("DuckDuckGo", "Google", "Startpage", "Brave")
        val current = activity.getSharedPreferences("settings", AppCompatActivity.MODE_PRIVATE)
            .getString("search_engine", "duckduckgo") ?: "duckduckgo"
        
        val checkedItem = when (current) {
            "google" -> 1
            "startpage" -> 2
            "brave" -> 3
            else -> 0
        }

        AlertDialog.Builder(activity)
            .setTitle("Search Engine")
            .setSingleChoiceItems(engines, checkedItem) { dialog, which ->
                val selected = when (which) {
                    1 -> "google"
                    2 -> "startpage"
                    3 -> "brave"
                    else -> "duckduckgo"
                }
                activity.getSharedPreferences("settings", AppCompatActivity.MODE_PRIVATE)
                    .edit().putString("search_engine", selected).apply()
                Toast.makeText(activity, "Search engine updated", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }
}
