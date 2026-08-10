package com.spoongecko.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BookmarkAdapter(
    private var entries: List<BookmarkEntry>,
    private val onClick: (BookmarkEntry) -> Unit,
    private val onEdit: (BookmarkEntry) -> Unit,
    private val onDelete: (BookmarkEntry) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.ViewHolder>() {

    // Issue #9: Expose items for DiffUtil
    val items: List<BookmarkEntry>
        get() = entries

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.bookmark_title)
        val url: TextView = view.findViewById(R.id.bookmark_url)
        val edit: ImageButton = view.findViewById(R.id.bookmark_edit)
        val delete: ImageButton = view.findViewById(R.id.bookmark_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.title.ifEmpty { entry.url }
        holder.url.text = entry.url
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.edit.setOnClickListener { onEdit(entry) }
        holder.delete.setOnClickListener { onDelete(entry) }
    }

    override fun getItemCount() = entries.size

    // Issue #9: Update items for DiffUtil integration
    fun updateItems(newEntries: List<BookmarkEntry>) {
        this.entries = newEntries
    }
}
