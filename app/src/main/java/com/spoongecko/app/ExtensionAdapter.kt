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
    private val onSettings: (WebExtension) -> Unit,
    private val onUninstall: (WebExtension) -> Unit
) : RecyclerView.Adapter<ExtensionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.ext_name)
        val version: TextView = view.findViewById(R.id.ext_version)
        val toggle: SwitchCompat = view.findViewById(R.id.ext_toggle)
        val settings: ImageButton = view.findViewById(R.id.ext_settings)
        val uninstall: ImageButton = view.findViewById(R.id.ext_uninstall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ext = extensions[position]
        val meta = ext.metaData
        val isEnabled = meta.disabledFlags == 0

        holder.name.text = meta.name ?: "Unknown"
        holder.version.text = "v${meta.version ?: "?"}"

        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = isEnabled
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            onToggle(ext, checked)
        }

        holder.settings.visibility = if (meta.optionsPageUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.settings.setOnClickListener { onSettings(ext) }
        holder.uninstall.setOnClickListener { onUninstall(ext) }
    }

    override fun getItemCount() = extensions.size
}
