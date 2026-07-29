package com.spoongecko.app

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionManager(private val runtime: GeckoRuntime, private val activity: Activity) {

    // Store browser actions (icons, popups, badges) for each extension
    private val browserActions = mutableMapOf<String, WebExtension.Action>()

    fun setupDelegates() {
        runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                if (activity.isFinishing || activity.isDestroyed) {
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                }
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Install Extension?")
                        .setMessage("Install ${extension.metaData.name}?")
                        .setPositiveButton("Install") { _, _ -> result.complete(WebExtension.PermissionPromptResponse(true, true, true)) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                        .setCancelable(false).show()
                }
                return result
            }
            override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)
            override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        })

        runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                activity.runOnUiThread { Toast.makeText(activity, "Installed: ${extension.metaData.name}", Toast.LENGTH_SHORT).show() }
            }
        })

        // Capture browser actions (popup URLs, icons, badges) from extensions
        runtime.webExtensionController.setActionDelegate(object : WebExtensionController.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                browserActions[extension.id] = action
            }
        })
    }

    fun getBrowserAction(extensionId: String): WebExtension.Action? = browserActions[extensionId]

    fun checkForUpdates() {
        runtime.webExtensionController.list().accept { extensions ->
            if (extensions.isNullOrEmpty()) return@accept
            for (ext in extensions) {
                try { runtime.webExtensionController.update(ext) } catch (e: Exception) { }
            }
        }
    }

    fun openFirstExtensionDashboard(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        runtime.webExtensionController.list().accept { extensions ->
            if (extensions.isNullOrEmpty()) { onError("No extensions installed."); return@accept }
            val extWithDashboard = extensions.firstOrNull { it.metaData.optionsPageUrl != null }
            if (extWithDashboard != null) {
                onSuccess(extWithDashboard.metaData.optionsPageUrl!!)
            } else {
                onError("This extension has no settings page.")
            }
        }
    }
}
