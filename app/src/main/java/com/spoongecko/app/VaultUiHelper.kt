package com.spoongecko.app

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray

data class VaultCredential(
    val host: String,
    val username: String,
    val password: String
)

class VaultUiHelper(
    private val activity: AppCompatActivity,
    private val vaultManager: SecureCredentialManager,
    private val onExportCsv: () -> Unit,
    private val onImportCsv: () -> Unit
) {
    fun showVault() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_vault, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_vault)
        val emptyText = view.findViewById<TextView>(R.id.vault_empty)
        val countLabel = view.findViewById<TextView>(R.id.vault_count)
        val searchBox = view.findViewById<EditText>(R.id.vault_search)
        recycler.layoutManager = LinearLayoutManager(activity)

        var allCredentials = loadCredentials()

        fun updateList(query: String) {
            val filtered = if (query.isEmpty()) {
                allCredentials
            } else {
                allCredentials.filter { 
                    it.host.contains(query, ignoreCase = true) || 
                    it.username.contains(query, ignoreCase = true) 
                }
            }
            
            countLabel.text = "${filtered.size} saved login" + if (filtered.size != 1) "s" else ""
            if (filtered.isEmpty()) {
                recycler.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
                recycler.adapter = CredentialAdapter(
                    credentials = filtered,
                    onEdit = { cred ->
                        val input = EditText(activity)
                        input.hint = "New password"
                        AlertDialog.Builder(activity)
                            .setTitle("Edit password")
                            .setMessage("${cred.username} @ ${cred.host}")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val newPass = input.text.toString()
                                if (newPass.isNotEmpty()) {
                                    vaultManager.editCredentialPassword(cred.host, cred.username, newPass)
                                    Toast.makeText(activity, "Password updated.", Toast.LENGTH_SHORT).show()
                                    dialog.dismiss(); showVault()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    },
                    onDelete = { cred ->
                        AlertDialog.Builder(activity)
                            .setTitle("Delete login")
                            .setMessage("Delete ${cred.username} @ ${cred.host}?")
                            .setPositiveButton("Delete") { _, _ ->
                                vaultManager.deleteCredentials(cred.host, cred.username)
                                Toast.makeText(activity, "Deleted.", Toast.LENGTH_SHORT).show()
                                dialog.dismiss(); showVault()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                )
            }
        }

        updateList("")

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<ImageButton>(R.id.vault_export)?.setOnClickListener { onExportCsv() }
        view.findViewById<ImageButton>(R.id.vault_import)?.setOnClickListener { onImportCsv() }

        dialog.show()
    }

    private fun loadCredentials(): List<VaultCredential> {
        val list = mutableListOf<VaultCredential>()
        try {
            val arr = JSONArray(vaultManager.getAllCredentialsAsJson())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VaultCredential(
                        host = obj.optString("host", ""),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}
