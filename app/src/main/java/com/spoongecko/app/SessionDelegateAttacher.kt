package com.spoongecko.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

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
                // Inject an unobtrusive "Local network page (HTTP)" banner when browsing
                // a private/local host over plain HTTP so the user is aware.
                if (logicalUrl.startsWith("http://") && LocalNetworkPolicy.isLocalUrl(logicalUrl)) {
                    val bannerJs = "javascript:(function(){" +
                        "if(document.getElementById('_spoon_local_banner'))return;" +
                        "var b=document.createElement('div');" +
                        "b.id='_spoon_local_banner';" +
                        "b.style.cssText='position:fixed;bottom:0;left:0;right:0;background:#1a237e;color:#e8eaf6;" +
                        "font-size:12px;text-align:center;padding:4px 8px;z-index:2147483647;" +
                        "font-family:sans-serif;pointer-events:none;';" +
                        "b.textContent='\uD83C\uDFE0 Local network page (HTTP)';" +
                        "document.body && document.body.appendChild(b);})();"
                    session.loadUri(bannerJs)
                }
                if (logicalUrl != "about:blank" && !it.startsWith("data:") && !it.startsWith("moz-extension:") && !it.startsWith("spoonvault://") && !it.startsWith("javascript:")) {
                    // Issue #5: Use BackgroundExecutor instead of Thread.start()
                    BackgroundExecutor.execute { dbHelper.addHistory(it, tab.title) }
                }
                onTabStateChanged(tab)
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack; onTabStateChanged(tab) }
        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward; onTabStateChanged(tab) }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            val isSslError = error.category == WebRequestError.ERROR_CATEGORY_SECURITY
            val isLocal = uri != null && LocalNetworkPolicy.isLocalUrl(uri)

            // One-time automatic HTTP fallback for local hosts with SSL errors
            if (isSslError && isLocal && uri != null && uri.startsWith("https://", ignoreCase = true)) {
                val host = LocalNetworkPolicy.extractHost(uri)
                if (host != null) LocalNetworkPolicy.clearHttpsVerified(host, activity)
                val httpUrl = "http://" + uri.removePrefix("https://")
                // Navigate to the HTTP version automatically – no user tap needed
                activity.runOnUiThread { session.loadUri(httpUrl) }
                // Return a minimal transitional page while the redirect fires
                val transitHtml = "<html><body style='background:#121212;color:#aaa;font-family:sans-serif;" +
                    "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;'>" +
                    "<p>Switching to HTTP for local network page…</p></body></html>"
                return GeckoResult.fromValue(
                    "data:text/html;base64," + Base64.encodeToString(transitHtml.toByteArray(), Base64.DEFAULT)
                )
            }

            val html = "<html><body style='background:#121212;color:#fff;font-family:sans-serif;" +
                "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;" +
                "flex-direction:column;text-align:center;padding:24px;'>" +
                "<h2>⚠️ Page failed to load</h2><p>${error.message ?: "Unknown error"}</p>" +
                "</body></html>"
            return GeckoResult.fromValue(
                "data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.DEFAULT)
            )
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

    private fun createPromptDelegate(tab: TabInfo) = object : GeckoSession.PromptDelegate {
        override fun onLoginSave(session: GeckoSession, request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSaveOption>): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (request.options.isNotEmpty()) {
                val entry = request.options[0].value
                val origin = entry.origin ?: ""
                val username = entry.username ?: ""
                val password = entry.password ?: ""
                if (origin.isNotEmpty() && username.isNotEmpty()) {
                    // Issue #5: Use BackgroundExecutor instead of blocking UI thread
                    BackgroundExecutor.execute {
                        vaultManager.saveCredentials(origin, username, password)
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Saved login for $origin", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return GeckoResult.fromValue(request.confirm(request.options[0]))
            }
            return GeckoResult.fromValue(request.dismiss())
        }

        override fun onLoginSelect(session: GeckoSession, request: GeckoSession.PromptDelegate.AutocompleteRequest<org.mozilla.geckoview.Autocomplete.LoginSelectOption>): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (request.options.isNotEmpty()) {
                return GeckoResult.fromValue(request.confirm(request.options[0]))
            }
            return GeckoResult.fromValue(request.dismiss())
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
            if (host.isNotEmpty() && user.isNotEmpty()) {
                // Issue #5: Use BackgroundExecutor to avoid blocking
                BackgroundExecutor.execute {
                    vaultManager.saveCredentials(host, user, pass)
                }
            }
        } catch (_: Exception) { }
    }
}
