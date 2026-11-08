package com.spoongecko.browser;

import java.util.Locale;

/**
 * Pure-Java helpers for addresses on the user's own network (routers, modems,
 * NAS boxes, ...). No Android dependencies and no DNS: every decision is made
 * from the text of the address alone.
 *
 * Three separate questions are answered here, because they have different
 * answers for the same address:
 *
 *   1. toNavigableUrl()             what should typing "192.168.1.1" load?
 *   2. needsLocalNetworkPermission() will Android 17 block it until the user
 *                                    grants "local network" access?
 *   3. isOverrideEligible()         may the user click through a certificate
 *                                    warning for it?
 */
public final class LocalAddress {

    private LocalAddress() {}

    // ------------------------------------------------------------------
    // 1. Address-bar input -> URL
    // ------------------------------------------------------------------

    /**
     * Turns what the user typed into a URL, or returns null when it should be
     * treated as a search.
     *
     * Everything that worked before is unchanged: input with a scheme is used
     * as typed, other input containing a dot gets "https://". The only new
     * behaviour is for IP literals and "localhost": those get "http://"
     * (routers and similar devices almost never serve HTTPS on the default
     * port), or "https://" when the user typed the usual HTTPS port 443/8443.
     */
    public static String toNavigableUrl(String input) {
        if (input == null) return null;
        final String s = input.trim();
        if (s.isEmpty() || s.indexOf(' ') >= 0) return null;     // a search, as before
        if (s.contains("://")) return s;                          // used as typed, as before

        final Authority a = parseAuthority(s);
        if (a != null && (isIpLiteral(a.host) || "localhost".equals(a.host))) {
            final String scheme = (a.port == 443 || a.port == 8443) ? "https" : "http";
            final String hostPart = a.host.indexOf(':') >= 0 ? "[" + a.host + "]" : a.host;
            final String portPart = a.port >= 0 ? ":" + a.port : "";
            return scheme + "://" + hostPart + portPart + a.rest;
        }
        if (s.contains(".")) return "https://" + s;               // as before
        return null;
    }

    /** "https://x" -> "http://x" and vice versa; null for any other scheme. */
    public static String alternateScheme(String url) {
        if (url == null) return null;
        if (url.regionMatches(true, 0, "https://", 0, 8)) return "http://" + url.substring(8);
        if (url.regionMatches(true, 0, "http://", 0, 7)) return "https://" + url.substring(7);
        return null;
    }

    // ------------------------------------------------------------------
    // 2 + 3. Classification
    // ------------------------------------------------------------------

    /** Lower-case host of a URL that has "://", without brackets; else null. */
    public static String hostOf(String url) {
        if (url == null) return null;
        final int i = url.indexOf("://");
        if (i < 0) return null;
        final Authority a = parseAuthority(url.substring(i + 3));
        return a == null ? null : a.host;
    }

    public static boolean isIpLiteral(String host) {
        return ipBytes(host) != null;
    }

    /**
     * True for addresses Android's Local Network Protection (Android 17,
     * targetSdk 37+) gates behind the ACCESS_LOCAL_NETWORK permission:
     * 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10 (CGNAT, i.e. many
     * ISP gateways), multicast and broadcast; IPv6 link-local, unique-local
     * and multicast; plus ".local" (mDNS) names. Loopback is not included —
     * that is the device itself, not the network.
     */
    public static boolean needsLocalNetworkPermission(String host) {
        if (host == null) return false;
        final byte[] ip = ipBytes(host);
        if (ip != null) return isLocalNetworkIp(ip);
        return host.endsWith(".local");
    }

    /**
     * Whether a certificate warning may be clicked through for this host:
     * any IP literal (router, modem, ISP gateway, NAS ...) or a name that is
     * clearly local. Public domain names are never eligible, so a man-in-the-
     * middle on a real website still gets a hard stop.
     */
    public static boolean isOverrideEligible(String host) {
        if (host == null || host.isEmpty()) return false;
        return isIpLiteral(host) || isLocalHostname(host);
    }

    public static boolean isLocalHostname(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf('.') < 0) return true;                   // single label: "router", "nas"
        return host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".lan") || host.endsWith(".home.arpa")
                || host.endsWith(".internal") || host.endsWith(".localdomain")
                || host.endsWith(".home") || host.endsWith(".intranet");
    }

    // ------------------------------------------------------------------
    // Address ranges
    // ------------------------------------------------------------------

    static boolean isLocalNetworkIp(byte[] a) {
        if (a.length == 4) return isLocalNetworkV4(a[0] & 0xff, a[1] & 0xff, a[2] & 0xff, a[3] & 0xff);
        if (isV4Mapped(a)) {
            return isLocalNetworkV4(a[12] & 0xff, a[13] & 0xff, a[14] & 0xff, a[15] & 0xff);
        }
        final int b0 = a[0] & 0xff;
        final int b1 = a[1] & 0xff;
        if (b0 == 0xff) return true;                              // ff00::/8   multicast
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return true;      // fe80::/10  link-local
        return (b0 & 0xfe) == 0xfc;                               // fc00::/7   unique-local
    }

    private static boolean isLocalNetworkV4(int a, int b, int c, int d) {
        if (a == 10) return true;                                 // 10.0.0.0/8
        if (a == 172 && b >= 16 && b <= 31) return true;         // 172.16.0.0/12
        if (a == 192 && b == 168) return true;                    // 192.168.0.0/16
        if (a == 169 && b == 254) return true;                    // 169.254.0.0/16
        if (a == 100 && b >= 64 && b <= 127) return true;        // 100.64.0.0/10 (CGNAT)
        if (a >= 224 && a <= 239) return true;                    // 224.0.0.0/4 multicast
        return a == 255 && b == 255 && c == 255 && d == 255;      // broadcast
    }

    private static boolean isV4Mapped(byte[] a) {
        if (a.length != 16) return false;
        for (int i = 0; i < 10; i++) if (a[i] != 0) return false;
        return (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** host[:port] plus whatever follows it ("/path?query#fragment"). */
    static final class Authority {
        final String host;     // lower-case, no brackets, no trailing dot
        final int port;        // -1 when absent
        final String rest;

        Authority(String host, int port, String rest) {
            this.host = host;
            this.port = port;
            this.rest = rest;
        }
    }

    /** Parses text that starts at the authority (i.e. after any "scheme://"). */
    static Authority parseAuthority(String s) {
        if (s == null || s.isEmpty()) return null;
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String auth = s.substring(0, end);
        final String rest = s.substring(end);
        final int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        if (auth.isEmpty()) return null;

        String host;
        int port = -1;
        if (auth.charAt(0) == '[') {
            final int close = auth.indexOf(']');
            if (close < 0) return null;
            host = auth.substring(1, close);
            final String after = auth.substring(close + 1);
            if (!after.isEmpty()) {
                if (after.charAt(0) != ':') return null;
                if (after.length() > 1) {
                    port = parsePort(after.substring(1));
                    if (port < 0) return null;
                }
            }
        } else {
            final int colon = auth.indexOf(':');
            if (colon < 0) {
                host = auth;
            } else if (auth.indexOf(':', colon + 1) >= 0) {
                host = auth;                                      // bare IPv6, no port possible
            } else {
                host = auth.substring(0, colon);
                port = parsePort(auth.substring(colon + 1));
                if (port < 0) return null;
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.length() > 1 && host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return null;
        return new Authority(host, port, rest);
    }

    private static int parsePort(String p) {
        if (p == null || p.isEmpty() || p.length() > 5) return -1;
        int v = 0;
        for (int i = 0; i < p.length(); i++) {
            final char c = p.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v <= 65535 ? v : -1;
    }

    /** 4 bytes for an IPv4 literal, 16 for IPv6, null if not an IP literal. */
    static byte[] ipBytes(String host) {
        if (host == null || host.isEmpty()) return null;
        return host.indexOf(':') >= 0 ? parseIPv6(host) : parseIPv4(host);
    }

    /** Strict dotted quad. Leading zeros are rejected: they are octal in some parsers. */
    static byte[] parseIPv4(String s) {
        if (s == null) return null;
        final String[] p = s.split("\\.", -1);
        if (p.length != 4) return null;
        final byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            final String d = p[i];
            if (d.isEmpty() || d.length() > 3) return null;
            if (d.length() > 1 && d.charAt(0) == '0') return null;
            int v = 0;
            for (int k = 0; k < d.length(); k++) {
                final char c = d.charAt(k);
                if (c < '0' || c > '9') return null;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    /** RFC 4291 text form, incl. "::" compression and a dotted-quad tail. Zone ids are ignored. */
    static byte[] parseIPv6(String input) {
        if (input == null) return null;
        String s = input;
        final int pct = s.indexOf('%');
        if (pct >= 0) s = s.substring(0, pct);
        if (s.isEmpty() || s.length() > 45 || s.indexOf(':') < 0) return null;

        final int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null;   // at most one "::"
        // ":::" is two overlapping "::" — reject explicitly
        if (s.contains(":::")) return null;

        final int[] head;
        final int[] tail;
        if (dbl >= 0) {
            head = groups(s.substring(0, dbl), false);
            tail = groups(s.substring(dbl + 2), true);
            if (head == null || tail == null) return null;
            if (head.length + tail.length > 7) return null;            // "::" stands for >= 1 group
        } else {
            head = groups(s, true);
            tail = new int[0];
            if (head == null || head.length != 8) return null;
        }

        final int[] all = new int[8];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, 8 - tail.length, tail.length);
        final byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (all[i] >> 8);
            out[2 * i + 1] = (byte) all[i];
        }
        return out;
    }

    /** Colon-separated 16-bit groups; an IPv4 tail counts as two groups. Empty string -> no groups. */
    private static int[] groups(String s, boolean allowV4Tail) {
        if (s.isEmpty()) return new int[0];
        final String[] parts = s.split(":", -1);
        final int[] tmp = new int[parts.length + 1];
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            final String g = parts[i];
            if (g.isEmpty()) return null;
            if (g.indexOf('.') >= 0) {
                if (!allowV4Tail || i != parts.length - 1) return null;
                final byte[] v4 = parseIPv4(g);
                if (v4 == null) return null;
                tmp[n++] = ((v4[0] & 0xff) << 8) | (v4[1] & 0xff);
                tmp[n++] = ((v4[2] & 0xff) << 8) | (v4[3] & 0xff);
            } else {
                if (g.length() > 4) return null;
                int v = 0;
                for (int k = 0; k < g.length(); k++) {
                    final int d = Character.digit(g.charAt(k), 16);
                    if (d < 0) return null;
                    v = (v << 4) | d;
                }
                tmp[n++] = v;
            }
        }
        final int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }
}
