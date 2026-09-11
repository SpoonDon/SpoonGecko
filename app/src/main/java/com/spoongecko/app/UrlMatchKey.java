package com.spoongecko.app;

import android.net.Uri;

import java.util.Locale;

final class UrlMatchKey {

    final String host;
    final String domain;
    final String path;

    private UrlMatchKey(String host, String domain, String path) {
        this.host = host;
        this.domain = domain;
        this.path = path;
    }

    static UrlMatchKey from(String rawUrl) {
        if (rawUrl == null) return new UrlMatchKey("", "", "");
        String input = rawUrl.trim();
        if (input.isEmpty()) return new UrlMatchKey("", "", "");

        String host = "";
        String path = "";
        String parseable = input.contains("://") ? input : "https://" + input;
        try {
            Uri uri = Uri.parse(parseable);
            String h = uri.getHost();
            if (h != null && !h.isEmpty()) host = h.toLowerCase(Locale.ROOT);
            String p = uri.getPath();
            if (p != null) path = p;
        } catch (Exception ignored) {
        }

        if (host.isEmpty()) host = legacyHost(input);

        String domain = PublicSuffixList.registrableDomain(host);
        return new UrlMatchKey(host, domain, normalizePath(path));
    }

    private static String legacyHost(String input) {
        String value = input.toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(']') < colon) value = value.substring(0, colon);
        while (value.startsWith(".")) value = value.substring(1);
        return value.trim();
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.isEmpty() || p.equals("/")) return "";
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    boolean pathMatchesStored(String storedPath) {
        if (storedPath == null || storedPath.isEmpty()) return true;
        if (path == null || path.isEmpty()) return false;
        if (path.equals(storedPath)) return true;
        return path.startsWith(storedPath + "/") || storedPath.startsWith(path + "/");
    }
}
