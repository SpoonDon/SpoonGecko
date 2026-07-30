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
        // 1. Prompt Delegate (Handles install permissions and updates)
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

        // 2. Addon Manager Delegate (Handles install/uninstall events)
        runtime.webExtensionController.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                activity.runOnUiThread { Toast.makeText(activity, "Installed: ${extension.metaData.name}", Toast.LENGTH_SHORT).show() }
            }
        })
        
        // NOTE: ActionDelegate and TabDelegate are removed. 
        // We handle extension popups via our custom BottomSheet menu instead.
    }

    fun checkForUpdates() {
        runtime.webExtensionController.list().accept { extensions ->
            if (extensions.isNullOrEmpty()) return@accept
            for (ext in extensions) {
                try { runtime.webExtensionController.update(ext) } catch (e: Exception) { }
            }
        }
    }
}
