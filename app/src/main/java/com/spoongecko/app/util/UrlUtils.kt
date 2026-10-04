package com.spoongecko.app.util

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlUtils {

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val LOCALHOST_REGEX = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")
    private val DOMAIN_REGEX = Regex("^[\\w-]+(\\.[\\w-]+)+(/.*)?$")

    /**
     * Turn arbitrary user input into something GeckoView will accept:
     *  - full URLs pass through
     *  - bare domains get https://
     *  - anything else becomes a DuckDuckGo search
     */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"

        if (trimmed.startsWith("about:") || trimmed.startsWith("file:")) return trimmed

        if (SCHEME_REGEX.containsMatchIn(trimmed)) return trimmed

        if (LOCALHOST_REGEX.matches(trimmed) || DOMAIN_REGEX.matches(trimmed)) {
            return "https://$trimmed"
        }

        return searchUrl(trimmed)
    }

    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "https://duckduckgo.com/?q=$encoded"
    }

    fun prettify(url: String): String {
        if (url.isBlank() || url == "about:blank") return ""
        return try {
            val parsed = Uri.parse(url)
            val host = parsed.host ?: return url
            val path = parsed.path.orEmpty().takeIf { it != "/" } ?: ""
            host + path
        } catch (_: Exception) {
            url
        }
    }
}
