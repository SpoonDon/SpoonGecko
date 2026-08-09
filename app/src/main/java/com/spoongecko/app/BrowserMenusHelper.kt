package com.spoongecko.app

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

class BrowserMenusHelper(
    private val activity: Activity,
    private val dbHelper: DatabaseHelper,
    private val tabManager: TabManager,
    private val vaultManager: SecureCredentialManager,
    private val extensionManager: ExtensionManager,
    private val onExportVault: (String) -> Unit,
    private val onExitRequested: () -> Unit
) {

    // ================= Tab Manager Sheet =================

    fun openTabManager() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_tabs, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_tabs)
        recycler.layoutManager = LinearLayoutManager(activity)

        fun refresh() {
            val active = tabManager.activeTab ?: return
            recycler.adapter = TabAdapter(
                tabs = tabManager.tabs.toList(),
                activeTab = active,
                onClick = { tab ->
                    tabManager.switchToSession(tab)
                    dialog.dismiss()
                },
                onClose = { tab ->
                    if (tabManager.tabs.size <= 1) {
                        // Closing the last tab exits the app (existing TabManager behavior)
                        dialog.dismiss()
                        tabManager.closeSession(tab)
                    } else {
                        tabManager.closeSession(tab)
                        refresh()
                    }
                }
            )
        }
        refresh()

        view.findViewById<ImageButton>(R.id.btn_new_tab).setOnClickListener {
            tabManager.createNewSession()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ================= Main Menu Sheet =================

    fun showMenuOptions() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_menu, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.menu_add_bookmark).setOnClickListener {
            dialog.dismiss()
            addCurrentPageBookmark()
        }

        view.findViewById<TextView>(R.id.menu_bookmarks).setOnClickListener {
            dialog.dismiss()
            openBookmarksSheet()
        }

        view.findViewById<TextView>(R.id.menu_history).setOnClickListener {
            dialog.dismiss()
            openHistorySheet()
        }

        view.findViewById<TextView>(R.id.menu_search_engine).setOnClickListener {
            dialog.dismiss()
            showSearchEnginePicker()
        }

        view.findViewById<TextView>(R.id.menu_update_extensions).setOnClickListener {
            dialog.dismiss()
            extensionManager.checkForUpdates()
            Toast.makeText(activity, "Checking for extension updates...", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<TextView>(R.id.menu_export_vault).setOnClickListener {
            dialog.dismiss()
            val data = vaultManager.getAllCredentialsAsJson()
            if (data == "[]") {
                Toast.makeText(activity, "Vault is empty.", Toast.LENGTH_SHORT).show()
            } else {
                onExportVault(data)
            }
        }

        view.findViewById<TextView>(R.id.menu_exit).setOnClickListener {
            dialog.dismiss()
            onExitRequested()
        }

        dialog.show()
    }

    // ================= Bookmarks =================

    private fun addCurrentPageBookmark() {
        val tab = tabManager.activeTab
        if (tab == null || tab.url.isEmpty() || tab.url == "about:blank" || tab.url.startsWith("data:")) {
            Toast.makeText(activity, "Nothing to bookmark.", Toast.LENGTH_SHORT).show()
            return
        }
        val added = dbHelper.addBookmark(tab.url, tab.title)
        Toast.makeText(activity, if (added) "Bookmark added." else "Already bookmarked.", Toast.LENGTH_SHORT).show()
    }

    private fun openBookmarksSheet() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_bookmarks, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_bookmarks)
        recycler.layoutManager = LinearLayoutManager(activity)
        val sortSpinner = view.findViewById<Spinner>(R.id.bookmark_sort)

        val sortOptions = arrayOf("timestamp DESC", "timestamp ASC", "title ASC")
        val sortLabels = arrayOf("Newest first", "Oldest first", "Title A-Z")
        sortSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sortLabels)
        var sortBy = sortOptions[0]

        fun refresh() {
            recycler.adapter = BookmarkAdapter(
                entries = dbHelper.getBookmarks(sortBy),
                onClick = { entry ->
                    tabManager.activeTab?.session?.loadUri(entry.url)
                    dialog.dismiss()
                },
                onEdit = { entry ->
                    showBookmarkEditDialog(entry) { refresh() }
                },
                onDelete = { entry ->
                    dbHelper.deleteBookmark(entry.id)
                    refresh()
                }
            )
        }

        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selectedView: View?, position: Int, id: Long) {
                if (position in sortOptions.indices) {
                    sortBy = sortOptions[position]
                    refresh()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        view.findViewById<ImageButton>(R.id.btn_add_bookmark).setOnClickListener {
            addCurrentPageBookmark()
            refresh()
        }

        refresh()
        dialog.show()
    }

    private fun showBookmarkEditDialog(entry: BookmarkEntry, onSaved: () -> Unit) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val titleEdit = EditText(activity).apply {
            hint = "Title"
            setText(entry.title)
        }
        val urlEdit = EditText(activity).apply {
            hint = "URL"
            setText(entry.url)
        }
        container.addView(titleEdit)
        container.addView(urlEdit)

        AlertDialog.Builder(activity)
            .setTitle("Edit Bookmark")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                dbHelper.updateBookmark(entry.id, titleEdit.text.toString(), urlEdit.text.toString())
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= History =================

    private fun openHistorySheet() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_history, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        recycler.layoutManager = LinearLayoutManager(activity)
        val searchBox = view.findViewById<EditText>(R.id.history_search)
        val sortSpinner = view.findViewById<Spinner>(R.id.history_sort)

        val sortOptions = arrayOf("timestamp DESC", "timestamp ASC", "visit_count DESC", "title ASC")
        val sortLabels = arrayOf("Recent first", "Oldest first", "Most visited", "Title A-Z")
        sortSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sortLabels)
        var sortBy = sortOptions[0]

        fun refresh() {
            val query = searchBox.text.toString().trim()
            recycler.adapter = HistoryAdapter(
                entries = dbHelper.getHistory(query, sortBy),
                onClick = { entry ->
                    tabManager.activeTab?.session?.loadUri(entry.url)
                    dialog.dismiss()
                },
                onStar = { entry ->
                    val added = dbHelper.addBookmark(entry.url, entry.title)
                    Toast.makeText(activity, if (added) "Bookmark added." else "Already bookmarked.", Toast.LENGTH_SHORT).show()
                },
                onDelete = { entry ->
                    dbHelper.deleteHistory(entry.id)
                    refresh()
                }
            )
        }

        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selectedView: View?, position: Int, id: Long) {
                if (position in sortOptions.indices) {
                    sortBy = sortOptions[position]
                    refresh()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<ImageButton>(R.id.btn_delete_all_history).setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Clear History")
                .setMessage("Delete ALL browsing history?")
                .setPositiveButton("Delete") { _, _ ->
                    dbHelper.deleteAllHistory()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refresh()
        dialog.show()
    }

    // ================= Settings =================

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
