package com.spoongecko.app

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoStorageController

class BrowserMenusHelper(
    private val activity: AppCompatActivity,
    private val dbHelper: DatabaseHelper,
    private val tabManager: TabManager,
    private val runtime: GeckoRuntime,
    private val onNavigate: (String) -> Unit
) {

    fun openTabManager() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_tabs, null)
        
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_tabs)
        val btnNewTab = view.findViewById<ImageButton>(R.id.btn_new_tab)

        recycler.layoutManager = LinearLayoutManager(activity)
        
        val adapter = TabAdapter(
            tabs = tabManager.tabs,
            activeTab = tabManager.activeTab!!,
            onClick = { tab ->
                tabManager.switchToSession(tab)
                dialog.dismiss()
            },
            onClose = { tab ->
                tabManager.closeSession(tab)
                dialog.dismiss()
                if (tabManager.tabs.isNotEmpty()) openTabManager() // Refresh sheet
            }
        )
        recycler.adapter = adapter

        btnNewTab.setOnClickListener {
            tabManager.createNewSession()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    fun showMenuOptions() {
        val options = arrayOf("Bookmarks", "History", "Clear Browsing Data")
        AlertDialog.Builder(activity)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openBookmarks()
                    1 -> openHistory()
                    2 -> showClearDataDialog()
                }
            }
            .show()
    }

    private fun openBookmarks() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_bookmarks, null)
        
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_bookmarks)
        val btnAdd = view.findViewById<ImageButton>(R.id.btn_add_bookmark)
        val sortSpinner = view.findViewById<Spinner>(R.id.bookmark_sort)

        recycler.layoutManager = LinearLayoutManager(activity)

        val sortOptions = arrayOf("Newest First", "Oldest First", "A-Z")
        sortSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sortOptions)
        
        var currentSort = "timestamp DESC"
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentSort = when (position) {
                    1 -> "timestamp ASC"
                    2 -> "title ASC"
                    else -> "timestamp DESC"
                }
                loadBookmarks(recycler, currentSort, dialog)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnAdd.setOnClickListener {
            val tab = tabManager.activeTab
            if (tab != null && tab.url != "about:blank" && !tab.url.startsWith("data:")) {
                val added = dbHelper.addBookmark(tab.url, tab.title)
                if (added) {
                    Toast.makeText(activity, "Bookmarked!", Toast.LENGTH_SHORT).show()
                    loadBookmarks(recycler, currentSort, dialog)
                } else {
                    Toast.makeText(activity, "Already bookmarked", Toast.LENGTH_SHORT).show()
                }
            }
        }

        loadBookmarks(recycler, currentSort, dialog)
        dialog.setContentView(view)
        dialog.show()
    }

    private fun loadBookmarks(recycler: RecyclerView, sortBy: String, dialog: BottomSheetDialog) {
        val bookmarks = dbHelper.getBookmarks(sortBy)
        recycler.adapter = BookmarkAdapter(
            entries = bookmarks,
            onClick = { entry ->
                onNavigate(entry.url)
                dialog.dismiss()
            },
            onEdit = { entry ->
                val builder = AlertDialog.Builder(activity)
                val input = EditText(activity).apply { setText(entry.title) }
                builder.setTitle("Edit Bookmark Title")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        dbHelper.updateBookmark(entry.id, input.text.toString(), entry.url)
                        loadBookmarks(recycler, sortBy, dialog)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onDelete = { entry ->
                dbHelper.deleteBookmark(entry.id)
                loadBookmarks(recycler, sortBy, dialog)
            }
        )
    }

    private fun openHistory() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_history, null)
        
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        val btnDeleteAll = view.findViewById<ImageButton>(R.id.btn_delete_all_history)
        val searchInput = view.findViewById<EditText>(R.id.history_search)
        val sortSpinner = view.findViewById<Spinner>(R.id.history_sort)

        recycler.layoutManager = LinearLayoutManager(activity)

        val sortOptions = arrayOf("Most Recent", "Oldest First", "Most Visited")
        sortSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sortOptions)
        
        var currentSort = "timestamp DESC"
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentSort = when (position) {
                    1 -> "timestamp ASC"
                    2 -> "visit_count DESC"
                    else -> "timestamp DESC"
                }
                loadHistory(recycler, searchInput.text.toString(), currentSort, dialog)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Clear History?")
                .setMessage("This will delete all browsing history.")
                .setPositiveButton("Delete") { _, _ ->
                    dbHelper.deleteAllHistory()
                    loadHistory(recycler, searchInput.text.toString(), currentSort, dialog)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadHistory(recycler, s.toString(), currentSort, dialog)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadHistory(recycler, "", currentSort, dialog)
        dialog.setContentView(view)
        dialog.show()
    }

    private fun loadHistory(recycler: RecyclerView, search: String, sortBy: String, dialog: BottomSheetDialog) {
        val history = dbHelper.getHistory(search, sortBy)
        recycler.adapter = HistoryAdapter(
            entries = history,
            onClick = { entry ->
                onNavigate(entry.url)
                dialog.dismiss()
            },
            onStar = { entry ->
                val added = dbHelper.addBookmark(entry.url, entry.title)
                if (added) Toast.makeText(activity, "Bookmarked!", Toast.LENGTH_SHORT).show()
                else Toast.makeText(activity, "Already bookmarked", Toast.LENGTH_SHORT).show()
            },
            onDelete = { entry ->
                dbHelper.deleteHistory(entry.id)
                loadHistory(recycler, search, sortBy, dialog)
            }
        )
    }

    private fun showClearDataDialog() {
        val options = arrayOf("Clear History", "Clear Cookies & Site Data")
        AlertDialog.Builder(activity)
            .setTitle("Clear Browsing Data")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        dbHelper.deleteAllHistory()
                        Toast.makeText(activity, "History cleared", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        // Clear GeckoView cookies and site data
                        val flags = GeckoStorageController.ClearFlags.COOKIES or GeckoStorageController.ClearFlags.SITE_DATA
                        runtime.storageController.clearData(flags)
                        Toast.makeText(activity, "Cookies and site data cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
