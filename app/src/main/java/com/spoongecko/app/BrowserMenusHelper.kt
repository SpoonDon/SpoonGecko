package com.spoongecko.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.WebExtension

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
    private val vaultUi = VaultUiHelper(activity, vaultManager)

    // ================= MAIN MENU =================
    fun showMenuOptions() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_menu, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.menu_new_tab).setOnClickListener {
            dialog.dismiss(); tabManager.createNewSession()
        }
        view.findViewById<TextView>(R.id.menu_add_bookmark).setOnClickListener {
            dialog.dismiss(); addCurrentPageBookmark()
        }
        view.findViewById<TextView>(R.id.menu_bookmarks).setOnClickListener {
            dialog.dismiss(); openBookmarks()
        }
        view.findViewById<TextView>(R.id.menu_history).setOnClickListener {
            dialog.dismiss(); openHistory()
        }
        view.findViewById<TextView>(R.id.menu_vault).setOnClickListener {
            dialog.dismiss(); vaultUi.showVault()
        }
        view.findViewById<TextView>(R.id.menu_extensions).setOnClickListener {
            dialog.dismiss(); openExtensions()
        }
        view.findViewById<TextView>(R.id.menu_search_engine).setOnClickListener {
            dialog.dismiss(); showSearchEnginePicker()
        }
        // Red exit button -> exits directly, NO confirmation (by design)
        view.findViewById<TextView>(R.id.menu_exit).setOnClickListener {
            dialog.dismiss(); onExitRequested()
        }
        dialog.show()
    }

    // ================= TAB MANAGER (swipe-to-close) =================
    fun openTabManager() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_tabs, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_tabs)
        val countLabel = view.findViewById<TextView>(R.id.tab_count)
        recycler.layoutManager = LinearLayoutManager(activity)

        fun refresh() {
            val active = tabManager.activeTab ?: return
            countLabel.text = "${tabManager.tabs.size} open tab" + if (tabManager.tabs.size != 1) "s" else ""
            recycler.adapter = TabAdapter(
                tabs = tabManager.tabs.toList(),
                activeTab = active,
                onClick = { tab -> tabManager.switchToSession(tab); dialog.dismiss() },
                onClose = { tab ->
                    // Closing the LAST remaining tab -> asks for confirmation (TabManager.onLastTabClosed)
                    tabManager.closeSession(tab)
                    if (tabManager.tabs.isNotEmpty()) refresh()
                }
            )
        }

        // Swipe left/right to close a tab
        val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                val tab = tabManager.tabs.getOrNull(pos) ?: return
                tabManager.closeSession(tab)
                if (tabManager.tabs.isEmpty()) dialog.dismiss() else refresh()
            }
        }
        ItemTouchHelper(swipe).attachToRecyclerView(recycler)

        view.findViewById<ImageButton>(R.id.btn_new_tab).setOnClickListener {
            tabManager.createNewSession(); dialog.dismiss()
        }

        refresh()
        dialog.show()
    }

    // ================= EXTENSIONS =================
    fun openExtensions() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_extensions, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_extensions)
        val emptyText = view.findViewById<TextView>(R.id.ext_empty)
        recycler.layoutManager = LinearLayoutManager(activity)

        fun refresh() {
            extensionManager.listExtensions { extensions ->
                activity.runOnUiThread {
                    if (extensions.isEmpty()) {
                        recycler.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                    } else {
                        recycler.visibility = View.VISIBLE
                        emptyText.visibility = View.GONE
                        recycler.adapter = ExtensionAdapter(
                            extensions = extensions,
                            onToggle = { ext, enable ->
                                extensionManager.toggleExtension(ext, enable) { refresh() }
                            },
                            onSettings = { ext -> extensionManager.openSettings(ext) },
                            onUninstall = { ext ->
                                AlertDialog.Builder(activity)
                                    .setTitle("Remove extension")
                                    .setMessage("Remove \"${ext.metaData.name}\"?")
                                    .setPositiveButton("Remove") { _, _ ->
                                        extensionManager.uninstallExtension(ext) { refresh() }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        )
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.ext_install_url).setOnClickListener {
            val input = EditText(activity)
            input.hint = "https://example.com/extension.xpi"
            AlertDialog.Builder(activity)
                .setTitle("Install from URL")
                .setView(input)
                .setPositiveButton("Install") { _, _ ->
                    val url = input.text.toString().trim()
                    if (url.isNotEmpty()) extensionManager.installFromUrl(url)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        view.findViewById<TextView>(R.id.ext_install_file).setOnClickListener {
            dialog.dismiss(); onInstallExtensionFromFile()
        }
        view.findViewById<TextView>(R.id.ext_update_all).setOnClickListener {
            extensionManager.updateAll()
            Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<TextView>(R.id.ext_backup).setOnClickListener {
            dialog.dismiss(); onBackupExtensions()
        }
        view.findViewById<TextView>(R.id.ext_restore).setOnClickListener {
            dialog.dismiss(); onRestoreExtensions()
        }

        refresh()
        dialog.show()
    }

    // ================= BOOKMARKS =================
    private fun addCurrentPageBookmark() {
        val tab = tabManager.activeTab ?: return
        if (tab.url.isEmpty() || tab.url == "about:blank" || tab.url.startsWith("data:")) {
            Toast.makeText(activity, "Nothing to bookmark.", Toast.LENGTH_SHORT).show()
            return
        }
        val added = dbHelper.addBookmark(tab.url, tab.title)
        Toast.makeText(activity, if (added) "Bookmark added." else "Already bookmarked.", Toast.LENGTH_SHORT).show()
    }

    private fun openBookmarks() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_bookmarks, null)
        dialog.setContentView(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_bookmarks)
        recycler.layoutManager = LinearLayoutManager(activity)
        val sortSpinner = view.findViewById<Spinner>(R.id.bookmark_sort)

        val sortOptions = arrayOf("timestamp DESC", "timestamp ASC", "title ASC")
        val sortLabels = arrayOf("Newest first", "Oldest first", "Title A-Z")
        sortSpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sortLabels)
        var sortBy = sortOptions[0]

        fun refresh() {
            recycler.adapter = BookmarkAdapter(
                entries = dbHelper.getBookmarks(sortBy),
                onClick = { entry -> dialog.dismiss(); onNavigate(entry.url) },
                onEdit = { entry ->
                    val input = EditText(activity)
                    input.setText(entry.title)
                    AlertDialog.Builder(activity)
                        .setTitle("Edit bookmark")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            dbHelper.updateBookmark(entry.id, input.text.toString(), entry.url)
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                },
                onDelete = { entry -> dbHelper.deleteBookmark(entry.id); refresh() }
            )
        }

        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position in sortOptions.indices) { sortBy = sortOptions[position]; refresh() }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        view.findViewById<ImageButton>(R.id.btn_add_bookmark).setOnClickListener {
            addCurrentPageBookmark(); refresh()
        }

        refresh()
        dialog.show()
    }

    // ================= HISTORY =================
    private fun openHistory() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_history, null)
        dialog.setContentView(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        recycler.layoutManager = LinearLayoutManager(activity)
        val searchBox = view.findViewById<EditText>(R.id.history_search)
        val sortSpinner = view.findViewById<Spinner>(R.id.history_sort)

        val sortOptions = arrayOf("timestamp DESC", "timestamp ASC", "visit_count DESC", "title ASC")
        val sortLabels = arrayOf("Recent first", "Oldest first", "Most visited", "Title A-Z")
        sortSpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sortLabels)
        var sortBy = sortOptions[0]

        fun refresh() {
            recycler.adapter = HistoryAdapter(
                entries = dbHelper.getHistory(searchBox.text.toString().trim(), sortBy),
                onClick = { entry -> dialog.dismiss(); onNavigate(entry.url) },
                onStar = { entry ->
                    val added = dbHelper.addBookmark(entry.url, entry.title)
                    Toast.makeText(activity, if (added) "Bookmark added." else "Already bookmarked.", Toast.LENGTH_SHORT).show()
                },
                onDelete = { entry -> dbHelper.deleteHistory(entry.id); refresh() }
            )
        }

        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position in sortOptions.indices) { sortBy = sortOptions[position]; refresh() }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        view.findViewById<ImageButton>(R.id.btn_delete_all_history).setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Clear history")
                .setMessage("Delete ALL browsing history?")
                .setPositiveButton("Delete") { _, _ -> dbHelper.deleteAllHistory(); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refresh()
        dialog.show()
    }

    // ================= SEARCH ENGINE =================
    private fun showSearchEnginePicker() {
        val engines = arrayOf("brave", "duckduckgo", "google", "startpage")
        val labels = arrayOf("Brave Search", "DuckDuckGo", "Google", "Startpage")
        val prefs = activity.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("search_engine", "brave") ?: "brave"
        val checked = engines.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("Search Engine")
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.edit().putString("search_engine", engines[which]).apply()
                Toast.makeText(activity, "Search engine set to ${labels[which]}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
