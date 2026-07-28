package com.spoongecko.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TabAdapter(
    private val tabs: List<TabInfo>,
    private val activeTab: TabInfo,
    private val onClick: (TabInfo) -> Unit,
    private val onClose: (TabInfo) -> Unit
) : RecyclerView.Adapter<TabAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tab_title)
        val url: TextView = view.findViewById(R.id.tab_url)
        val closeBtn: ImageButton = view.findViewById(R.id.tab_close)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title
        holder.url.text = tab.url
        holder.itemView.setOnClickListener { onClick(tab) }
        holder.closeBtn.setOnClickListener { onClose(tab) }
        
        // Visual indicator for active tab
        holder.itemView.alpha = if (tab == activeTab) 1.0f else 0.6f
    }

    override fun getItemCount() = tabs.size
}
