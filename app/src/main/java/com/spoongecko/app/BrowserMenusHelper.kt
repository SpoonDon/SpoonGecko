package com.spoongecko.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
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

    fun showMenuOptions(anchorView: android.view.View) {
        val popup = android.widget.PopupMenu(activity, anchorView, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> {
                    tabManager.createNewSession()
                    true
                }
                R.id.menu_add_bookmark -> {
                    val tab = tabManager.activeTab
                    if (tab != null && tab.url.isNotEmpty()) {
                        dbHelper.addBookmark(tab.url, tab.title)
                        android.widget.Toast.makeText(activity, "Bookmark added", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.menu_bookmarks -> { showBookmarks(); true }
                R.id.menu_history -> { showHistory(); true }
                R.id.menu_downloads -> { showDownloads(); true }
                R.id.menu_find_in_page -> { showFindInPage(); true }
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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { load(s.toString()) }
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
        val input = EditText(activity)
        input.hint = "Find in page..."
        AlertDialog.Builder(activity)
            .setTitle("Find")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val q = input.text.toString()
                if (q.isNotEmpty()) tabManager.activeTab?.session?.finder?.find(q, 0)
            }
            .setNeutralButton("Prev") { _, _ -> 
                val q = input.text.toString()
                if (q.isNotEmpty()) tabManager.activeTab?.session?.finder?.find(q, org.mozilla.geckoview.GeckoSession.FINDER_FIND_BACKWARDS) 
            }
            .setNegativeButton("Cancel", null)
            .show()
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
