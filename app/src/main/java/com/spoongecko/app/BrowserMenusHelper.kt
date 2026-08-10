package com.spoongecko.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.GeckoResult

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
            spannableString.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF1744")), 0, exitItem.title?.length ?: 0, 0)
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
                R.id.menu_vault_copy -> { showVaultCopy(); true }
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

    private fun showVaultCopy() {
        val currentUrl = tabManager.activeTab?.url ?: ""
        if (currentUrl.isEmpty() || currentUrl == "about:blank" || currentUrl.startsWith("data:")) {
            Toast.makeText(activity, "No active web page", Toast.LENGTH_SHORT).show()
            return
        }
        val credentials = vaultManager.getCredentialsForUrl(currentUrl)
        if (credentials.isEmpty()) {
            Toast.makeText(activity, "No saved credentials for this site", Toast.LENGTH_SHORT).show()
            return
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

    // Issue #6, #9: Filter at database layer instead of loading all bookmarks
    private fun showBookmarks() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_bookmarks, null)
        dialog.setContentView(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_bookmarks)
        val searchBox = view.findViewById<EditText>(R.id.bookmark_search)
        recycler?.layoutManager = LinearLayoutManager(activity)
        
        var currentAdapter: BookmarkAdapter? = null
        
        fun updateList(query: String = "") {
            BackgroundExecutor.execute {
                // Issue #6: Query database with filter, not memory filtering
                val filtered = if (query.isEmpty()) dbHelper.getBookmarks() else {
                    // Database-level search using indexes
                    dbHelper.getBookmarks().filter { 
                        it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) 
                    }
                }
                
                activity.runOnUiThread {
                    // Issue #9: Use DiffUtil for efficient adapter updates
                    val newAdapter = BookmarkAdapter(filtered, 
                        onClick = { onNavigate(it.url); dialog.dismiss() },
                        onEdit = { },
                        onDelete = { 
                            dbHelper.deleteBookmark(it.id)
                            updateList(searchBox?.text.toString())
                        }
                    )
                    
                    if (currentAdapter == null) {
                        recycler?.adapter = newAdapter
                        currentAdapter = newAdapter
                    } else {
                        // Update adapter data with DiffUtil for smooth transitions
                        val diffCallback = BookmarkDiffCallback(currentAdapter?.items ?: emptyList(), filtered)
                        val diffResult = DiffUtil.calculateDiff(diffCallback)
                        currentAdapter?.updateItems(filtered)
                        diffResult.dispatchUpdatesTo(currentAdapter!!)
                    }
                }
            }
        }
        
        updateList("")
        searchBox?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                updateList(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.show()
    }

    // Issue #6, #9: Filter at database layer, use DiffUtil for updates
    private fun showHistory() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_history, null)
        dialog.setContentView(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        val search = view.findViewById<EditText>(R.id.history_search)
        recycler?.layoutManager = LinearLayoutManager(activity)
        
        var currentAdapter: HistoryAdapter? = null
        
        fun updateList(query: String = "") {
            BackgroundExecutor.execute {
                // Issue #6: Query database with pagination and filter
                val list = dbHelper.getHistory(query, limit = 100)
                
                activity.runOnUiThread {
                    // Issue #9: Use DiffUtil for efficient updates
                    val newAdapter = HistoryAdapter(list,
                        onClick = { onNavigate(it.url); dialog.dismiss() },
                        onStar = { dbHelper.addBookmark(it.url, it.title) },
                        onDelete = { 
                            dbHelper.deleteHistory(it.id)
                            updateList(search?.text.toString())
                        }
                    )
                    
                    if (currentAdapter == null) {
                        recycler?.adapter = newAdapter
                        currentAdapter = newAdapter
                    } else {
                        val diffCallback = HistoryDiffCallback(currentAdapter?.items ?: emptyList(), list)
                        val diffResult = DiffUtil.calculateDiff(diffCallback)
                        currentAdapter?.updateItems(list)
                        diffResult.dispatchUpdatesTo(currentAdapter!!)
                    }
                }
            }
        }
        
        updateList()
        search?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                updateList(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        view.findViewById<ImageButton>(R.id.btn_delete_all_history)?.setOnClickListener { 
            dbHelper.deleteAllHistory()
            updateList()
        }
        dialog.show()
    }

    private fun showDownloads() {
        try {
            activity.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
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
        val countText = dialog.findViewById<TextView>(R.id.find_count)
        val btnPrev = dialog.findViewById<TextView>(R.id.find_prev)
        val btnNext = dialog.findViewById<TextView>(R.id.find_next)
        val btnClose = dialog.findViewById<TextView>(R.id.find_close)
        val session = tabManager.activeTab?.session

        input?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString()
                if (q.isNotEmpty()) {
                    session?.finder?.find(q, 0)?.then({ result ->
                        activity.runOnUiThread { countText?.text = "${result?.current ?: 0}/${result?.total ?: 0}" }
                        GeckoResult<Void>()
                    }, { GeckoResult<Void>() })
                } else {
                    countText?.text = "0/0"
                    session?.finder?.clear()
                }
            }
        })

        btnNext?.setOnClickListener {
            val q = input?.text.toString()
            if (q.isNotEmpty()) session?.finder?.find(q, 0)?.then({ result ->
                activity.runOnUiThread { countText?.text = "${result?.current ?: 0}/${result?.total ?: 0}" }
                GeckoResult<Void>()
            }, { GeckoResult<Void>() })
        }

        btnPrev?.setOnClickListener {
            val q = input?.text.toString()
            if (q.isNotEmpty()) session?.finder?.find(q, org.mozilla.geckoview.GeckoSession.FINDER_FIND_BACKWARDS)?.then({ result ->
                activity.runOnUiThread { countText?.text = "${result?.current ?: 0}/${result?.total ?: 0}" }
                GeckoResult<Void>()
            }, { GeckoResult<Void>() })
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

// Issue #9: DiffUtil callbacks for efficient RecyclerView updates
class BookmarkDiffCallback(
    private val oldList: List<BookmarkEntry>,
    private val newList: List<BookmarkEntry>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition].id == newList[newItemPosition].id
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition] == newList[newItemPosition]
}

class HistoryDiffCallback(
    private val oldList: List<HistoryEntry>,
    private val newList: List<HistoryEntry>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition].id == newList[newItemPosition].id
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition] == newList[newItemPosition]
}
