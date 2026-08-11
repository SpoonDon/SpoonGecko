package com.spoongecko.app

import java.net.URLEncoder
import java.util.Locale

object UrlNormalizer {

    private val URL_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val HOST_WITH_OPTIONAL_PORT_REGEX = Regex("^[a-zA-Z0-9.-]+(:\\d{1,5})?$")
    private val LOCAL_HOST_REGEX = Regex("^(localhost|.*\\.localhost)(:\\d{1,5})?$")

    fun normalize(rawInput: String): String {
        val input = rawInput.trim()

        if (input.isEmpty()) {
            return ""
        }

        if (input.any { it.isWhitespace() }) {
            return search(input)
        }

        val lower = input.lowercase(Locale.ROOT)

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val host = input.substringAfter("://", "")
            if (host.isBlank() || host.any { it.isWhitespace() }) {
                return search(input)
            }
            return input
        }

        if (URL_SCHEME_REGEX.containsMatchIn(input)) {
            return search(input)
        }

        if (isLocalTarget(input)) {
            return "http://$input"
        }

        if (looksLikePublicHost(input)) {
            return "https://$input"
        }

        return search(input)
    }

    private fun search(query: String): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            .replace("+", "%20")

        return "https://duckduckgo.com/?q=$encoded"
    }

    private fun isLocalTarget(input: String): Boolean {
        val lower = input.lowercase(Locale.ROOT)

        if (LOCAL_HOST_REGEX.matches(lower)) {
            return true
        }

        if (lower == "::1" || lower == "[::1]" || lower.startsWith("[::1]:")) {
            return true
        }

        if (lower.startsWith("fe80:") || lower.startsWith("[fe80:")) {
            return true
        }

        if (
            lower.startsWith("fc") ||
            lower.startsWith("fd") ||
            lower.startsWith("[fc") ||
            lower.startsWith("[fd")
        ) {
            return true
        }

        val host = hostWithoutPort(input)

        if (host.endsWith(".local", ignoreCase = true)) {
            return true
        }

        if (host.endsWith(".localhost", ignoreCase = true)) {
            return true
        }

        if (isPrivateIpv4(host)) {
            return true
        }

        return false
    }

    private fun looksLikePublicHost(input: String): Boolean {
        if (HOST_WITH_OPTIONAL_PORT_REGEX.matches(input)) {
            val host = hostWithoutPort(input)
            return host.contains('.') && !isLocalTarget(host)
        }

        if (input.startsWith("[")) {
            val host = hostWithoutPort(input)
            return host.contains(':') && !isLocalTarget(host)
        }

        return false
    }

    private fun hostWithoutPort(input: String): String {
        if (input.startsWith("[")) {
            val end = input.indexOf(']')
            if (end > 0) {
                return input.substring(1, end)
            }
        }

        val firstColon = input.indexOf(':')
        val lastColon = input.lastIndexOf(':')

        return if (firstColon != -1 && firstColon == lastColon) {
            input.substring(0, firstColon)
        } else {
            input
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val match = IPV4_REGEX.matchEntire(host) ?: return false

        val octets = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }

        if (octets.size != 4 || octets.any { it > 255 }) {
            return false
        }

        return when (octets[0]) {
            127 -> true
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            169 -> octets[1] == 254
            else -> false
        }
    }
}
