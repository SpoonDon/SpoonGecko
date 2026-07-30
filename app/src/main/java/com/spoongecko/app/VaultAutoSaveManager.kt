package com.spoongecko.app

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/**
 * Manages the vault auto-save WebExtension.
 * Responsible for:
 * - Installing the bundled extension
 * - Receiving native messages from the content script
 * - Presenting save prompts to the user
 */
class VaultAutoSaveManager(
    private val runtime: GeckoRuntime,
    private val activity: Activity,
    private val vaultManager: SecureCredentialManager
) {

    companion object {
        private const val EXTENSION_ID = "vault-autosave@spoongecko.app"
        private const val EXTENSION_URL = "resource://android/assets/extensions/vault-autosave/"
    }

    fun installExtension() {
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_URL, EXTENSION_ID).accept(
            { extension ->
                if (extension != null) {
                    registerMessageDelegate(extension)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Vault AutoSave enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "AutoSave failed: ${throwable?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun registerMessageDelegate(extension: WebExtension) {
        runtime.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageDelegate.Sender
                ): GeckoResult<Any>? {
                    if (nativeApp == EXTENSION_ID && message is Map<*, *>) {
                        val action = message["action"] as? String
                        if (action == "save") {
                            val host = message["host"] as? String ?: return null
                            val username = message["username"] as? String ?: ""
                            val password = message["password"] as? String ?: return null
                            
                            handleSaveRequest(host, username, password)
                        }
                    }
                    return null
                }
            }
        )
    }

    private fun handleSaveRequest(host: String, username: String, password: String) {
        if (password.isEmpty()) return

        val ignored = activity.getSharedPreferences("vault_ignored", Context.MODE_PRIVATE)
            .getBoolean(host, false)
        if (ignored) return

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Save Credentials?")
                .setMessage("Save login for $host?\n\nUsername: ${username.ifEmpty { "(empty)" }}")
                .setPositiveButton("Save") { _, _ ->
                    vaultManager.saveCredentials(host, username, password)
                    Toast.makeText(activity, "Saved to vault.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Not Now", null)
                .setNeutralButton("Never") { _, _ ->
                    activity.getSharedPreferences("vault_ignored", Context.MODE_PRIVATE)
                        .edit().putBoolean(host, true).apply()
                }
                .show()
        }
    }
}
