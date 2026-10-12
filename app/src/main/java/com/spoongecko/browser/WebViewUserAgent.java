package com.spoongecko.browser;

import android.net.Uri;

/**
 * Provides the WebView-style User-Agent string we send to private/local
 * hosts, plus the classifier that decides which hosts count as "local".
 *
 * Why this exists: many routers and ISP modems do User-Agent sniffing. When
 * they see a WebView UA (contains "; wv" or "Version/4.0"), they serve their
 * admin UI over plain HTTP because they know mobile WebView apps often fail
 * on self-signed certificates. When they see a desktop-class Firefox or
 * Chrome UA, they force an HTTPS redirect with a self-signed cert — which
 * GeckoView has no public API to bypass (unlike Android WebView, which
 * exposes WebViewClient.onReceivedSslError).
 *
 * The result is that SpoonGecko, by default, cannot reach router admin pages
 * that a WebView-based browser can reach with no effort. Sending the WebView
 * UA to local targets only restores that parity without changing behaviour
 * on any public site.
 */
public final class WebViewUserAgent {

    /**
     * Conservative Android WebView 4.0 UA.
     *
     * Contains the two markers routers sniff for:
     *   - "; wv"          -> signals "this is an embedded WebView"
     *   - "Version/4.0"   -> the legacy Android WebView version token
     *
     * Looks enough like Chrome to pass generic UA checks, but carries the
     * WebView markers so vendor middleware treats it as an app view rather
     * than a desktop browser.
     */
    public static final String VALUE =
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 " +
            "Mobile Safari/537.36";

    private WebViewUserAgent() {}

    /**
     * True when the URL targets a private-network host or a known router
     * vendor hostname. Public IPs, public domains and all non-http(s)
     * schemes return false.
     */
    public static boolean isLocalTarget(String url) {
        if (url == null || url.isEmpty()) return false;
        String scheme = Uri.parse(url).getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) return false;

        String host = Uri.parse(url).getHost();
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase();

        // Exact hostnames -------------------------------------------------
        if (host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")) return true;

        // Common router vendor hostnames ----------------------------------
        // These are the hostnames routers advertise on the sticker; when
        // typed, the router serves a self-signed cert for them. We treat
        // them as local so the WebView UA kicks in.
        String[] vendorHosts = {
                "tplinkwifi.net", "tplinklogin.net",
                "routerlogin.net", "routerlogin.com",
                "router.asus.com", "asusrouter.com",
                "dlinkrouter.local", "dlinkrouter",
                "tendawifi.com", "mwlogin.net",
                "miwifi.com", "pi.hole"
        };
        for (String v : vendorHosts) {
            if (host.equals(v) || host.endsWith("." + v)) return true;
        }

        // .local mDNS names ----------------------------------------------
        if (host.endsWith(".local")) return true;

        // IPv4 private / link-local / loopback / CGNAT -------------------
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            try {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                if (a == 10) return true;
                if (a == 127) return true;
                if (a == 192 && b == 168) return true;
                if (a == 172 && b >= 16 && b <= 31) return true;
                if (a == 169 && b == 254) return true;
                if (a == 100 && b >= 64 && b <= 127) return true; // CGNAT
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }
}
