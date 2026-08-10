package com.spoongecko.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.*

class SessionDelegateAttacher(
    private val activity: AppCompatActivity,
    private val runtime: GeckoRuntime,
    private val dbHelper: DatabaseHelper,
    private val vaultManager: SecureCredentialManager,
    private val onTabStateChanged: (TabInfo) -> Unit,
    private val onFullScreenRequested: (Boolean) -> Unit
) {
    private val downloadableExtensions = setOf(
        ".pdf", ".zip", ".rar", ".7z", ".apk", ".tar", ".gz",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".mp3", ".mp4", ".mkv", ".avi", ".webm",
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
        ".txt", ".csv", ".json", ".xml", ".iso", ".exe"
    )

    fun attach(tab: TabInfo) {
        tab.session.navigationDelegate = createNavigationDelegate(tab)
        tab.session.contentDelegate  = createContentDelegate(tab)
        tab.session.promptDelegate = createPromptDelegate(tab)
    }

    private fun createPromptDelegate(tab: TabInfo) = object : GeckoSession.PromptDelegate {
        override fun onLoginSave(session: GeckoSession, login: Autocomplete.LoginEntry): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val origin = login.origin ?: login.host ?: ""
            val user = login.username ?: ""
            val pass = login.password ?: ""
            if (origin.isNotEmpty() && user.isNotEmpty()) {
                vaultManager.saveCredentials(origin, user, pass)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Saved login for $origin", Toast.LENGTH_SHORT).show()
                }
            }
            return GeckoResult.fromValue(allow())
        }
    }

    private fun createNavigationDelegate(tab: TabInfo) = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            if (uri.startsWith("spoonvault://save")) {
                handleAutoSaveUri(uri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            if (uri.endsWith(".xpi", ignoreCase = true) ||
                (uri.contains("addons.mozilla.org") && uri.contains("/downloads/"))
            ) {
                runtime.webExtensionController.install(uri).accept(
                    { ext -> activity.runOnUiThread { Toast.makeText(activity, "Installed: ${ext?.metaData?.name}", Toast.LENGTH_SHORT).show() } },
                    { t   -> activity.runOnUiThread { Toast.makeText(activity, "Install failed: ${t?.message}", Toast.LENGTH_SHORT).show() } }
                )
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            if (isDownloadable(uri)) {
                startDownload(uri, guessFileName(uri))
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }

        override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
            url?.let {
                val isCustomNewTab = it.startsWith("data:text/html;charset=utf-8,<html><head><meta name='color-scheme' content='dark'>")
                val logicalUrl = if (isCustomNewTab) "about:blank" else it
                tab.url = logicalUrl
                if (logicalUrl != "about:blank" && !it.startsWith("data:") && !it.startsWith("moz-extension:") && !it.startsWith("spoonvault://") && !it.startsWith("javascript:")) {
                    Thread { dbHelper.addHistory(it, tab.title) }.start()
                }
                onTabStateChanged(tab)
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack; onTabStateChanged(tab) }
        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward; onTabStateChanged(tab) }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            val isLocalIp = uri?.matches(Regex("^(http|https)://(\\d{1,3}\\.){3}\\d{1,3}.*")) == true || uri?.contains("localhost") == true
            val isSslError = error.category == WebRequestError.ERROR_SECURITY
            
            var actionHtml = ""
            if (isSslError && isLocalIp) {
                val httpUrl = uri?.replace("https://", "http://") ?: ""
                actionHtml = "<br><a href='$httpUrl' style='color:#4CAF50;font-size:18px;'>⚠️ Router/Local IP detected SSL error. Tap here to try HTTP instead.</a>"
            }

            val html = "<html><body style='background:#121212;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;flex-direction:column;text-align:center;padding:20px;box-sizing:border-box;'><h1>Connection Failed</h1><p>Cannot reach ${uri ?: "this site"}</p>$actionHtml</body></html>"
            return GeckoResult.fromValue("data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.DEFAULT))
        }
    }

    private fun createContentDelegate(tab: TabInfo) = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            title?.let {
                tab.title = if (it.startsWith("data:") || it == "about:blank" || it.isEmpty()) "New Tab" else it
                onTabStateChanged(tab)
            }
        }
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            activity.runOnUiThread { onFullScreenRequested(fullScreen) }
        }
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val uri = response.uri ?: return
            val fileName = guessFileName(uri)
            startDownload(uri, fileName)
        }
    }

    private fun isDownloadable(uri: String): Boolean {
        return try {
            val path = Uri.parse(uri).path?.lowercase() ?: ""
            downloadableExtensions.any { path.endsWith(it) }
        } catch (_: Exception) { false }
    }

    private fun guessFileName(uri: String): String {
        return try {
            val path = Uri.parse(uri).lastPathSegment ?: "download"
            if (path.contains('.')) path else "$path.download"
        } catch (_: Exception) { "download" }
    }

    private fun startDownload(uri: String, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(uri))
                .setTitle(fileName)
                .setDescription("Downloading via Spoon Gecko")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            activity.runOnUiThread { Toast.makeText(activity, "Downloading: $fileName", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            activity.runOnUiThread { Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun handleAutoSaveUri(uri: String) {
        try {
            val parsed = Uri.parse(uri)
            val host = parsed.getQueryParameter("host") ?: ""
            val user = parsed.getQueryParameter("user") ?: ""
            val pass = parsed.getQueryParameter("pass") ?: ""
            if (host.isNotEmpty() && user.isNotEmpty()) vaultManager.saveCredentials(host, user, pass)
        } catch (_: Exception) { }
    }
}
