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

    fun setupDelegates() {
    // 1. Prompt Delegate (Installs, Permissions, Updates)
    runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            if (activity.isFinishing || activity.isDestroyed) return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            activity.runOnUiThread {
                val permsText = (permissions.toList() + origins.toList()).joinToString("\n")
                AlertDialog.Builder(activity)
                    .setTitle("Install ${extension.metaData.name}?")
                    .setMessage("Requires permissions:\n\n$permsText")
                    .setPositiveButton("Install") { _, _ -> result.complete(WebExtension.PermissionPromptResponse(true, true, true)) }
                    .setNegativeButton("Cancel") { _, _ -> result.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                    .setCancelable(false).show()
            }
            return result
        }
        override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)
    })

    // 2. Addon Manager Delegate
    runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalled(extension: WebExtension) {
            activity.runOnUiThread { Toast.makeText(activity, "Installed: ${extension.metaData.name}", Toast.LENGTH_SHORT).show() }
        }
        override fun onUninstalled(extension: WebExtension) {
            activity.runOnUiThread { Toast.makeText(activity, "Uninstalled: ${extension.metaData.name}", Toast.LENGTH_SHORT).show() }
        }
    })

    // 3. Action Delegate (Crucial for Firefox-like Popup handling)
    runtime.webExtensionController.setActionDelegate(object : WebExtensionController.ActionDelegate {
        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) {
            // This triggers when an extension's browser action icon is clicked.
            // You can route this to MainActivity's openExtensionPopup() via an interface or EventBus.
            activity.runOnUiThread {
                Toast.makeText(activity, "Opening ${extension.metaData.name} popup...", Toast.LENGTH_SHORT).show()
                // Note: Call your MainActivity popup logic here
            }
        }
    })

    // 4. Tab Delegate (Allows extensions to open new tabs)
    runtime.webExtensionController.setTabDelegate(object : WebExtensionController.TabDelegate {
        override fun onNewTab(extension: WebExtension, url: String): GeckoResult<GeckoSession>? {
            // Return null to let GeckoView handle it internally within the extension sandbox,
            // or return a new GeckoSession if you want to pull it into your main UI tabs.
            return GeckoResult.fromValue(null)
        }
    })
}
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
