package com.spoongecko.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CredentialAdapter(
    private val credentials: List<VaultCredential>,
    private val onEdit: (VaultCredential) -> Unit,
    private val onDelete: (VaultCredential) -> Unit
) : RecyclerView.Adapter<CredentialAdapter.ViewHolder>() {

    private val revealed = mutableSetOf<Int>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val host: TextView = view.findViewById(R.id.cred_host)
        val username: TextView = view.findViewById(R.id.cred_username)
        val password: TextView = view.findViewById(R.id.cred_password)
        val reveal: ImageButton = view.findViewById(R.id.cred_reveal)
        val edit: ImageButton = view.findViewById(R.id.cred_edit)
        val delete: ImageButton = view.findViewById(R.id.cred_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_credential, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cred = credentials[position]
        val isRevealed = revealed.contains(position)

        holder.host.text = cred.host
        holder.username.text = cred.username
        holder.password.text = if (isRevealed) cred.password else "••••••••"
        holder.reveal.setImageResource(    
            if (isRevealed) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_view
        )

        holder.reveal.setOnClickListener {
            if (revealed.contains(position)) revealed.remove(position) else revealed.add(position)
            notifyItemChanged(position)
        }
        holder.edit.setOnClickListener { onEdit(cred) }
        holder.delete.setOnClickListener { onDelete(cred) }
    }

    override fun getItemCount() = credentials.size
}
