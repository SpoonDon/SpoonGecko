package com.spoongecko.app

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import java.net.URLEncoder
import java.util.regex.Pattern

object UrlRouter {

    private val ipv4Pattern = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$")
    private val domainPattern = Pattern.compile("^[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,}$")

    fun loadUrlOrSearch(query: String, session: GeckoSession, context: Context) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        val isIp = ipv4Pattern.matcher(trimmed).matches()
        val isDomain = domainPattern.matcher(trimmed).matches()
        val isLocalhost = trimmed.equals("localhost", ignoreCase = true) || trimmed.startsWith("localhost:", ignoreCase = true)
        val isUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") || isIp || isDomain || isLocalhost

        if (isUrl) {
            val finalUrl = when {
                trimmed.startsWith("http") -> trimmed
                isIp || isLocalhost -> "http://$trimmed"
                else -> "https://$trimmed"
            }
            session.loadUri(finalUrl)
        } else {
            val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            val engine = prefs.getString("search_engine", "brave")
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val searchUrl = when (engine) {
                "duckduckgo" -> "https://duckduckgo.com/?q=$encoded"
                "google" -> "https://www.google.com/search?q=$encoded"
                "startpage" -> "https://www.startpage.com/do/dsearch?query=$encoded"
                else -> "https://search.brave.com/search?q=$encoded"
            }
            session.loadUri(searchUrl)
        }
    }
}
