package com.spoongecko.browser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Detects local-network / router / ISP-admin hosts so the URL bar can
 * auto-prefix them with http:// instead of https://.
 *
 * Router admin pages almost never serve HTTPS with a trusted certificate.
 * Defaulting to https:// would produce a certificate warning (or GeckoView
 * silently blocking the load), which is what we were doing before.
 *
 * Recognizes: RFC1918 + loopback + link-local IPv4, IPv6 unique-local +
 * link-local, common mDNS-ish suffixes (.local, .lan, .home, .internal),
 * and well-known router vendor hostnames.
 */
public final class LocalHostPolicy {

    private static final Set<String> LOCAL_SUFFIXES = new HashSet<>(Arrays.asList(
            "local", "lan", "home", "internal", "intranet", "corp", "private",
            "test", "invalid", "localhost"
    ));

    private static final Set<String> KNOWN_ROUTER_HOSTS = new HashSet<>(Arrays.asList(
            "routerlogin.net", "routerlogin.com",
            "tplinklogin.net", "tplinkwifi.net",
            "tendawifi.com", "mwlogin.net",
            "router.asus.com", "asusrouter.com",
            "dlinkrouter.local", "dlink.local",
            "linksyssmartwifi.com",
            "my.router", "myrouter.local",
            "pi.hole", "home.arpa"
    ));

    private LocalHostPolicy() {}

    public static boolean isLocalHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);

        // Strip IPv6 brackets
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        // Strip a single trailing dot (FQDN form of a local name)
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return false;

        if (KNOWN_ROUTER_HOSTS.contains(h)) return true;
        if (isPrivateIPv4(h)) return true;
        if (isPrivateIPv6(h)) return true;

        for (String suffix : LOCAL_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static boolean isPrivateIPv4(String h) {
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255
                    || c < 0 || c > 255 || d < 0 || d > 255) return false;
            if (a == 10) return true;                            // 10.0.0.0/8
            if (a == 172 && b >= 16 && b <= 31) return true;     // 172.16.0.0/12
            if (a == 192 && b == 168) return true;               // 192.168.0.0/16
            if (a == 169 && b == 254) return true;               // 169.254.0.0/16
            if (a == 127) return true;                           // 127.0.0.0/8
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isPrivateIPv6(String h) {
        if (!h.contains(":")) return false;
        if ("::1".equals(h)) return true;
        // fc00::/7 unique local
        if (h.startsWith("fc") || h.startsWith("fd")) return true;
        // fe80::/10 link-local
        if (h.startsWith("fe8") || h.startsWith("fe9")
                || h.startsWith("fea") || h.startsWith("feb")) return true;
        return false;
    }
}
