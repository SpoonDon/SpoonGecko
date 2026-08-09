package com.spoongecko.app

import android.app.Activity
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray

class VaultUiHelper(
    private val activity: Activity,
    private val vaultManager: SecureCredentialManager,
    private val getCurrentUrl: () -> String?,
    private val onExport: (String) -> Unit
) {

    // ── Main entry: shows vault options ──────────────────────────────
    fun showVaultMenu() {
        val options = arrayOf(
            "Credentials for Current Site",
            "All Saved Credentials",
            "Add Credential Manually",
            "Export Vault"
        )
        AlertDialog.Builder(activity)
            .setTitle("Vault")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSiteCredentials()
                    1 -> showAllCredentials()
                    2 -> showAddCredentialDialog()
                    3 -> exportVault()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Credentials for the current site ─────────────────────────────
    fun showSiteCredentials() {
        val url = getCurrentUrl()
        if (url.isNullOrEmpty()) {
            Toast.makeText(activity, "No page loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val host = extractHost(url)
        if (host.isEmpty()) {
            Toast.makeText(activity, "No valid site detected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val jsonArray = JSONArray(vaultManager.getAllAccountsForHost(host))
            if (jsonArray.length() == 0) {
                Toast.makeText(activity, "No credentials saved for $host", Toast.LENGTH_SHORT).show()
                return
            }

            val dialog = BottomSheetDialog(activity)
            val scrollView = ScrollView(activity)
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
            scrollView.addView(container)

            container.addView(TextView(activity).apply {
                text = "Credentials for $host"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 32)
            })

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val username = obj.getString("username")
                container.addView(buildCredentialRow(host, username, dialog) { showSiteCredentials() })
            }

            container.addView(Button(activity).apply {
                text = "＋ Add Credential"
                setOnClickListener { dialog.dismiss(); showAddCredentialDialog(host) }
            })

            dialog.setContentView(scrollView)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Error loading credentials: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── All saved credentials grouped by host ────────────────────────
    fun showAllCredentials() {
        try {
            val jsonArray = JSONArray(vaultManager.getAllCredentialsAsJson())
            if (jsonArray.length() == 0) {
                Toast.makeText(activity, "Vault is empty", Toast.LENGTH_SHORT).show()
                return
            }

            val grouped = LinkedHashMap<String, MutableList<String>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val host = obj.getString("host")
                val username = obj.getString("username")
                grouped.getOrPut(host) { mutableListOf() }.add(username)
            }

            val dialog = BottomSheetDialog(activity)
            val scrollView = ScrollView(activity)
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
            scrollView.addView(container)

            container.addView(TextView(activity).apply {
                text = "All Saved Credentials"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 32)
            })

            for ((host, usernames) in grouped) {
                container.addView(TextView(activity).apply {
                    text = host
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 24, 0, 8)
                })
                for (username in usernames) {
                    container.addView(buildCredentialRow(host, username, dialog) { showAllCredentials() })
                }
            }

            dialog.setContentView(scrollView)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Error loading vault: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Build a single credential row ────────────────────────────────
    private fun buildCredentialRow(
        host: String,
        username: String,
        parentDialog: BottomSheetDialog,
        onRefresh: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(activity).apply {
                text = username
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    parentDialog.dismiss()
                    showEditCredentialDialog(host, username, onRefresh)
                }
            })

            addView(ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    parentDialog.dismiss()
                    confirmDeleteCredential(host, username, onRefresh)
                }
            })
        }
    }

    // ── Add credential dialog ────────────────────────────────────────
    fun showAddCredentialDialog(prefillHost: String = "") {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val hostEdit = EditText(activity).apply {
            hint = "Host (e.g. example.com)"
            setText(prefillHost)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val usernameEdit = EditText(activity).apply {
            hint = "Username / Email"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passwordEdit = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(hostEdit)
        container.addView(usernameEdit)
        container.addView(passwordEdit)

        AlertDialog.Builder(activity)
            .setTitle("Add Credential")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val h = hostEdit.text.toString().trim()
                val u = usernameEdit.text.toString().trim()
                val p = passwordEdit.text.toString()
                if (h.isNotEmpty() && u.isNotEmpty()) {
                    vaultManager.saveCredentials(h, u, p)
                    Toast.makeText(activity, "Credential saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Host and username are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Edit credential password ─────────────────────────────────────
    private fun showEditCredentialDialog(host: String, username: String, onSaved: () -> Unit) {
        val passwordEdit = EditText(activity).apply {
            hint = "New Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(activity)
            .setTitle("Edit Password\n$username @ $host")
            .setView(passwordEdit)
            .setPositiveButton("Save") { _, _ ->
                val newPassword = passwordEdit.text.toString()
                if (newPassword.isNotEmpty()) {
                    vaultManager.editCredentialPassword(host, username, newPassword)
                    Toast.makeText(activity, "Password updated", Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    Toast.makeText(activity, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Delete credential with confirmation ──────────────────────────
    private fun confirmDeleteCredential(host: String, username: String, onDeleted: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Delete Credential")
            .setMessage("Delete credential for $username @ $host?\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                vaultManager.deleteCredentials(host, username)
                Toast.makeText(activity, "Credential deleted", Toast.LENGTH_SHORT).show()
                onDeleted()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Export vault ─────────────────────────────────────────────────
    fun exportVault() {
        val data = vaultManager.getAllCredentialsAsJson()
        if (data == "[]") {
            Toast.makeText(activity, "Vault is empty — nothing to export", Toast.LENGTH_SHORT).show()
        } else {
            onExport(data)
        }
    }

    // ── Helper: extract host from URL ────────────────────────────────
    private fun extractHost(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.host ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
