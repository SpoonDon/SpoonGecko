package com.spoongecko.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val entries: List<HistoryEntry>,
    private val onClick: (HistoryEntry) -> Unit,
    private val onStar: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.history_title)
        val url: TextView = view.findViewById(R.id.history_url)
        val star: ImageButton = view.findViewById(R.id.history_star)
        val delete: ImageButton = view.findViewById(R.id.history_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.title.ifEmpty { entry.url }
        holder.url.text = entry.url
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.star.setOnClickListener { onStar(entry) }
        holder.delete.setOnClickListener { onDelete(entry) }
    }

    override fun getItemCount() = entries.size
}
