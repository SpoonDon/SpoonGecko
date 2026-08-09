package com.spoongecko.app

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionManager(private val runtime: GeckoRuntime, private val activity: Activity) {

    companion object {
        /**
         * IronFox's public AMO collection.
         * Mozilla restricts extension installs to Firefox-only, but a
         * curated collection page still exposes install buttons that
         * GeckoView's WebExtensionController can consume.
         */
        const val AMO_COLLECTION_URL =
            "https://addons.mozilla.org/firefox/collections/17798049/ironfox/"

        /** Fallback: full AMO with ?src=mobile for wider selection */
        const val AMO_FULL_URL = "https://addons.mozilla.org/firefox/"
    }

    // ── delegates (install prompts, lifecycle) ──────────────────────
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
                val perms = permissions.joinToString(", ")
                val originsStr = origins.joinToString(", ")
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Install Extension?")
                        .setMessage("Install ${extension.metaData.name}?\n\nPermissions:\n$perms\n\nOrigins:\n$originsStr")
                        .setPositiveButton("Install") { _, _ ->
                            result.complete(WebExtension.PermissionPromptResponse(true, true, true))
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                        }
                        .setCancelable(false)
                        .show()
                }
                return result
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        })

        runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Installed: ${extension.metaData.name}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // ── query installed extensions ──────────────────────────────────
    fun getInstalledExtensions(callback: (List<WebExtension>) -> Unit) {
        runtime.webExtensionController.list().accept { extensions ->
            activity.runOnUiThread { callback(extensions ?: emptyList()) }
        }
    }

    // ── enable / disable / uninstall ────────────────────────────────
    fun setEnabled(extension: WebExtension, enabled: Boolean) {
        runtime.webExtensionController.setEnabled(extension, enabled)
    }

    fun uninstall(extension: WebExtension) {
        runtime.webExtensionController.uninstall(extension).accept(
            { _ ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Uninstalled: ${extension.metaData.name}", Toast.LENGTH_SHORT).show()
                }
            },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Uninstall failed: ${throwable.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ── install from arbitrary .xpi URL ─────────────────────────────
    fun installFromUrl(url: String) {
        runtime.webExtensionController.install(url).accept(
            { ext ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Installed: ${ext?.metaData?.name}", Toast.LENGTH_SHORT).show()
                }
            },
            { throwable ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "Install failed: ${throwable.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ── check for updates on every installed extension ──────────────
    fun checkForUpdates() {
        runtime.webExtensionController.list().accept { extensions ->
            if (extensions.isNullOrEmpty()) return@accept
            for (ext in extensions) {
                try { runtime.webExtensionController.update(ext) } catch (_: Exception) { }
            }
        }
    }
}
