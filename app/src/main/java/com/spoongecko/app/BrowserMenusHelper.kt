package com.spoongecko.app

import android.view.LayoutInflater
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
    private val onRestoreExtensions: () -> Unit
) {

    fun showMenuOptions() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_menu, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.menu_new_tab)?.setOnClickListener {
            tabManager.createNewSession()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_add_bookmark)?.setOnClickListener {
            val tab = tabManager.activeTab
            if (tab != null && tab.url.isNotEmpty()) {
                dbHelper.addBookmark(tab.title, tab.url)
                Toast.makeText(activity, "Bookmark added", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_bookmarks)?.setOnClickListener {
            showBookmarks()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_history)?.setOnClickListener {
            showHistory()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_vault)?.setOnClickListener {
            val vaultUi = VaultUiHelper(activity, vaultManager)
            vaultUi.showVault()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_extensions)?.setOnClickListener {
            showExtensions()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_search_engine)?.setOnClickListener {
            showSearchEnginePicker()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_exit)?.setOnClickListener {
            onExitRequested()
            dialog.dismiss()
        }

        dialog.show()
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
                onClick = { tab ->
                    tabManager.switchToSession(tab)
                    dialog.dismiss()
                },
                onClose = { tab ->
                    tabManager.closeSession(tab)
                    dialog.dismiss()
                }
            )
            recyclerView?.adapter = adapter
        }

        view.findViewById<ImageButton>(R.id.btn_new_tab)?.setOnClickListener {
            tabManager.createNewSession()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showBookmarks() {
        try {
            val bookmarks = dbHelper.getBookmarks()
            if (bookmarks.isEmpty()) {
                Toast.makeText(activity, "No bookmarks yet", Toast.LENGTH_SHORT).show()
                return
            }
            val titles = bookmarks.map { it.title }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Bookmarks")
                .setItems(titles) { _, which ->
                    onNavigate(bookmarks[which].url)
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Error loading bookmarks", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHistory() {
        try {
            val history = dbHelper.getHistory()
            if (history.isEmpty()) {
                Toast.makeText(activity, "No history yet", Toast.LENGTH_SHORT).show()
                return
            }
            val titles = history.map { it.title }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("History")
                .setItems(titles) { _, which ->
                    onNavigate(history[which].url)
                }
                .setNeutralButton("Clear") { _, _ ->
                    dbHelper.deleteAllHistory()
                    Toast.makeText(activity, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Error loading history", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExtensions() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_extensions, null)
        dialog.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_extensions)
        recyclerView?.layoutManager = LinearLayoutManager(activity)

        view.findViewById<TextView>(R.id.ext_install_file)?.setOnClickListener {
            onInstallExtensionFromFile()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.ext_backup)?.setOnClickListener {
            onBackupExtensions()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.ext_restore)?.setOnClickListener {
            onRestoreExtensions()
            dialog.dismiss()
        }

        extensionManager.listExtensions { extensions ->
            activity.runOnUiThread {
                val adapter = ExtensionAdapter(
                    extensions = extensions,
                    onToggle = { ext, enabled ->
                        extensionManager.toggleExtension(ext, enabled) {}
                    },
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
