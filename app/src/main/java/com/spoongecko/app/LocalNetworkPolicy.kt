package com.spoongecko.app

import android.content.Context
import java.net.InetAddress

/**
 * Utilities for detecting local/private network targets and enforcing a
 * permanent HTTP-first policy for those hosts.
 *
 * Public internet hosts are unaffected – HTTPS behaviour there is unchanged.
 */
object LocalNetworkPolicy {

    // SharedPreferences key used for per-host protocol memory.
    private const val PREFS_NAME = "local_network_protocol_prefs"
    private const val KEY_HTTPS_TRY_FIRST = "https_first_for_local"

    // -------------------------------------------------------------------------
    // Host classification
    // -------------------------------------------------------------------------

    /**
     * Returns true when [host] resolves to a local / private network address
     * or matches a well-known local-network hostname pattern.
     *
     * This method is intentionally pure (no I/O) so it can be called on the
     * main thread and easily unit-tested.
     */
    fun isLocalHost(host: String): Boolean {
        if (host.isBlank()) return false

        // Exact / suffix hostname matches
        val lower = host.lowercase().trimEnd('.')
        if (lower == "localhost") return true
        if (lower.endsWith(".local")) return true

        // IPv6 – check well-known local prefixes textually to avoid DNS look-up
        if (isLocalIpv6(lower)) return true

        // IPv4 – parse octets directly
        val ipv4Octets = parseIpv4(lower)
        if (ipv4Octets != null) return isPrivateIpv4(ipv4Octets)

        // Hostname that looks like a bare IP range is already handled above.
        // Everything else (public domain names) is not local.
        return false
    }

    /**
     * Returns true if [url]'s host is a local/private network target.
     */
    fun isLocalUrl(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return isLocalHost(host)
    }

    // -------------------------------------------------------------------------
    // URL normalisation
    // -------------------------------------------------------------------------

    /**
     * If [url] targets a local host and the caller has not opted in to
     * HTTPS-first for local addresses, rewrite https:// → http://.
     *
     * For all other URLs the original string is returned unchanged.
     */
    fun normaliseLocalUrl(url: String, context: Context): String {
        if (!url.startsWith("https://", ignoreCase = true)) return url
        if (!isLocalUrl(url)) return url

        val host = extractHost(url) ?: return url
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Respect user opt-in to HTTPS-first for local hosts (default: off)
        val globalHttpsFirst = prefs.getBoolean(KEY_HTTPS_TRY_FIRST, false)
        // Per-host memory: if HTTPS has previously succeeded we keep it
        val httpsVerified = prefs.getBoolean("https_verified_$host", false)

        if (globalHttpsFirst || httpsVerified) return url

        return "http://" + url.removePrefix("https://")
    }

    // -------------------------------------------------------------------------
    // Per-host protocol memory
    // -------------------------------------------------------------------------

    /**
     * Record that HTTPS has been proven to work for [host].
     * Future calls to [normaliseLocalUrl] will preserve https:// for that host.
     */
    fun markHttpsVerified(host: String, context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("https_verified_$host", true).apply()
    }

    /**
     * Clear the HTTPS-verified flag for [host], reverting to the HTTP-first
     * default.  Called when an HTTPS attempt fails for a local host.
     */
    fun clearHttpsVerified(host: String, context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("https_verified_$host").apply()
    }

    // -------------------------------------------------------------------------
    // Internal helpers (internal visibility so they are reachable from tests)
    // -------------------------------------------------------------------------

    internal fun extractHost(url: String): String? {
        return try {
            val noScheme = url.substringAfter("://")
            // strip path, query, fragment
            noScheme.substringBefore("/").substringBefore("?").substringBefore("#")
                // strip port
                .let { if (it.startsWith("[")) it.substringBefore("]").removePrefix("[") else it.substringBefore(":") }
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    internal fun parseIpv4(host: String): IntArray? {
        // Strip port if present, but only for non-IPv6 literals
        val bare = host.substringBefore(":")
        val parts = bare.split(".")
        if (parts.size != 4) return null
        return try {
            IntArray(4) { parts[it].toInt().also { v -> if (v < 0 || v > 255) throw IllegalArgumentException() } }
        } catch (_: Exception) { null }
    }

    internal fun isPrivateIpv4(octets: IntArray): Boolean {
        val a = octets[0]; val b = octets[1]
        return when {
            a == 10 -> true                          // 10.0.0.0/8
            a == 127 -> true                          // 127.0.0.0/8 loopback
            a == 172 && b in 16..31 -> true           // 172.16.0.0/12
            a == 192 && b == 168 -> true              // 192.168.0.0/16
            a == 169 && b == 254 -> true              // 169.254.0.0/16 link-local
            a == 100 && b in 64..127 -> true          // 100.64.0.0/10 CGNAT
            else -> false
        }
    }

    internal fun isLocalIpv6(host: String): Boolean {
        // Remove surrounding brackets if present (URI notation)
        val h = host.trim('[', ']').lowercase()
        if (h == "::1") return true                              // loopback
        if (h.startsWith("fc") || h.startsWith("fd")) return true // unique local fc00::/7
        // Link-local fe80::/10 covers fe80:: – febf:: (first byte 0xfe, second byte 0x80–0xbf)
        if (h.length >= 4) {
            val firstGroup = h.substringBefore(":").padStart(4, '0')
            val byte1 = firstGroup.substring(0, 2).toIntOrNull(16) ?: return false
            val byte2 = firstGroup.substring(2, 4).toIntOrNull(16) ?: return false
            if (byte1 == 0xfe && byte2 in 0x80..0xbf) return true
        }
        return false
    }
}
