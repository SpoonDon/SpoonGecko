package com.spoongecko.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.geckoview.WebExtension

class ExtensionAdapter(
    private val extensions: List<WebExtension>,
    private val onToggle: (WebExtension, Boolean) -> Unit,
    private val onUninstall: (WebExtension) -> Unit
) : RecyclerView.Adapter<ExtensionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.ext_name)
        val version: TextView = view.findViewById(R.id.ext_version)
        val toggle: SwitchCompat = view.findViewById(R.id.ext_toggle)
        val delete: ImageButton = view.findViewById(R.id.ext_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ext = extensions[position]
        val meta = ext.metaData
        holder.name.text = meta?.name ?: ext.id
        holder.version.text = "v${meta?.version ?: "?"}"

        // Prevent triggering the listener while we set the initial state
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = ext.isEnabled
        holder.toggle.setOnCheckedChangeListener { _, isChecked ->
            onToggle(ext, isChecked)
        }

        holder.delete.setOnClickListener { onUninstall(ext) }
    }

    override fun getItemCount() = extensions.size
}
