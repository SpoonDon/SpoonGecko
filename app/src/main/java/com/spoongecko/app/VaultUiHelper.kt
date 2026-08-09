package com.spoongecko.app

import android.view.LayoutInflater
import android.view.View
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
        recycler.layoutManager = LinearLayoutManager(activity)

        // Renamed to refreshVault() to avoid clashing with the private loadCredentials() below
        fun refreshVault() {
            val credentials = loadCredentials()
            countLabel.text = "${credentials.size} saved login" + if (credentials.size != 1) "s" else ""
            if (credentials.isEmpty()) {
                recycler.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
                recycler.adapter = CredentialAdapter(
                    credentials = credentials,
                    onEdit = { cred ->
                        val input = android.widget.EditText(activity)
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

        refreshVault()

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
