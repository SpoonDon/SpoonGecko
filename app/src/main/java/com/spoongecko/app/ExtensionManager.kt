package com.spoongecko.app

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionManager(private val runtime: GeckoRuntime, private val activity: Activity) {

    // Tracks the source URL of each installed extension (for update/backup/restore)
    private val sourcePrefs = activity.getSharedPreferences("extension_sources", Context.MODE_PRIVATE)

    fun setupDelegates() {
        runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                if (activity.isFinishing || activity.isDestroyed) {
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                }
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Install Extension?")
                        .setMessage("Install ${extension.metaData.name}?\n\nPermissions:\n${permissions.joinToString(", ")}")
                        .setPositiveButton("Install") { _, _ ->
                            result.complete(WebExtension.PermissionPromptResponse(true, true, true))
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                        }
                        .setCancelable(false).show()
                }
                return result
            }

            override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny>? =
                GeckoResult.fromValue(AllowOrDeny.ALLOW)

            override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny>? =
                GeckoResult.fromValue(AllowOrDeny.ALLOW)
        })

        runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Installed: ${extension.metaData.name}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun listExtensions(callback: (List<WebExtension>) -> Unit) {
        runtime.webExtensionController.list().accept { extensions ->
            callback(extensions ?: emptyList())
        }
    }

    fun installFromUrl(url: String) {
        runtime.webExtensionController.install(Uri.parse(url)).accept(
            { ext ->
                ext?.let { recordSource(it.id, url) }
                activity.runOnUiThread {
                    Toast.makeText(activity, "Installed: ${ext?.metaData?.name}", Toast.LENGTH_SHORT).show()
                }
            },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Install failed: ${throwable?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun installFromFile(uri: Uri) {
        // Take persistent read permission so GeckoView can read the .xpi
        try {
            activity.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        runtime.webExtensionController.install(uri).accept(
            { ext ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Installed: ${ext?.metaData?.name}", Toast.LENGTH_SHORT).show()
                }
            },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Install failed: ${throwable?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun toggleExtension(ext: WebExtension, enable: Boolean, callback: () -> Unit) {
        val source = WebExtension.EnableSource.USER
        val result = if (enable) {
            runtime.webExtensionController.enable(ext, source)
        } else {
            runtime.webExtensionController.disable(ext, source)
        }
        result.accept(
            { callback() },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Failed: ${throwable?.message}", Toast.LENGTH_SHORT).show()
                }
                callback()
            }
        )
    }

    fun uninstallExtension(ext: WebExtension, callback: () -> Unit) {
        runtime.webExtensionController.uninstall(ext).accept(
            {
                sourcePrefs.edit().remove(ext.id).apply()
                activity.runOnUiThread {
                    Toast.makeText(activity, "Removed: ${ext.metaData.name}", Toast.LENGTH_SHORT).show()
                }
                callback()
            },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Failed: ${throwable?.message}", Toast.LENGTH_SHORT).show()
                }
                callback()
            }
        )
    }

    fun updateAll() {
        runtime.webExtensionController.list().accept { extensions ->
            if (extensions.isNullOrEmpty()) return@accept
            for (ext in extensions) {
                try { runtime.webExtensionController.update(ext) } catch (_: Exception) {}
            }
        }
    }

    fun openSettings(ext: WebExtension) {
        val url = ext.metaData.optionsPageUrl
        if (!url.isNullOrEmpty()) {
            // Open the options page in the active tab via callback is not available here,
            // so open it directly via a new load on the active session is not accessible.
            // Instead, open via the runtime's default session is not available; use a toast fallback.
            // We open the options page by loading it into the current active session through a helper.
            (activity as? AppCompatActivity)?.let { act ->
                // Find GeckoView and load options page into active session if possible
                val geckoView = act.findViewById<org.mozilla.geckoview.GeckoView>(R.id.gecko_view)
                geckoView?.session?.loadUri(url)
            }
        } else {
            Toast.makeText(activity, "This extension has no settings page.", Toast.LENGTH_SHORT).show()
        }
    }

    fun backupToFile(uri: Uri) {
        listExtensions { extensions ->
            activity.runOnUiThread {
                try {
                    val arr = JSONArray()
                    for (ext in extensions) {
                        val obj = JSONObject()
                        obj.put("id", ext.id)
                        obj.put("name", ext.metaData.name ?: "")
                        obj.put("sourceUrl", sourcePrefs.getString(ext.id, "") ?: "")
                        obj.put("enabled", ext.metaData.disabledFlags == 0)
                        arr.put(obj)
                    }
                    val root = JSONObject()
                    root.put("version", 1)
                    root.put("extensions", arr)

                    activity.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(root.toString(2).toByteArray())
                    }
                    Toast.makeText(activity, "Backed up ${extensions.size} extensions.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(activity, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun restoreFromFile(uri: Uri) {
        try {
            val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val root = JSONObject(text)
            val arr = root.optJSONArray("extensions") ?: JSONArray()

            var installed = 0
            var skipped = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sourceUrl = obj.optString("sourceUrl", "")
                if (sourceUrl.isNotEmpty()) {
                    installFromUrl(sourceUrl)
                    installed++
                } else {
                    skipped++
                }
            }
            Toast.makeText(activity, "Restoring $installed extension(s)" +
                (if (skipped > 0) " ($skipped skipped, no source URL)" else ""), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordSource(extId: String, url: String) {
        sourcePrefs.edit().putString(extId, url).apply()
    }
}
