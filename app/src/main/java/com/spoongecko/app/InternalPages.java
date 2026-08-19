package com.spoongecko.app;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class InternalPages {

    private InternalPages() {}

    static String newTabPage(String bgHex, String fgHex, String message) {
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:" + bgHex + ";color:" + fgHex
                + ";font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh}"
                + "p{font-size:18px;opacity:0.55}</style></head><body><p>"
                + escapeHtml(message) + "</p></body></html>";
        return "data:text/html;charset=utf-8," + encodeForDataUri(html);
    }

    static String securityWarningDataUri(String uri) {
        String host = null;
        try {
            host = new URL(uri).getHost();
        } catch (Exception ignored) {}

        String safeHost = escapeHtml(host != null ? host : uri);
        String safeUri = escapeJs(uri);

        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;"
                + "min-height:100vh;margin:0;background:#111319;color:#E1E2E8}"
                + ".card{max-width:420px;padding:32px;text-align:center}"
                + "h2{font-size:20px;margin:0 0 8px 0;color:#FFB4AB}"
                + "p{font-size:14px;color:#C4C6D0;margin:0 0 24px 0;line-height:1.5}"
                + ".host{font-family:monospace;word-break:break-all;color:#E1E2E8}"
                + "button{background:#4d6bfe;color:#fff;border:none;padding:12px 24px;"
                + "border-radius:8px;font-size:16px;cursor:pointer}"
                + "button:hover{background:#3b54d0}"
                + ".cancel{background:none;color:#4d6bfe;border:1px solid #4d6bfe;margin-top:12px}"
                + ".cancel:hover{background:#2B3042}"
                + "</style></head><body><div class='card'>"
                + "<h2>Security Warning</h2>"
                + "<p>The certificate for <span class='host'>" + safeHost + "</span> is not trusted.<br>"
                + "Connecting to this site may expose your information.</p>"
                + "<button onclick='proceed()'>Proceed (unsafe)</button><br>"
                + "<button class='cancel' onclick='cancel()'>Go Back</button>"
                + "<script>"
                + "function proceed(){"
                + "document.addCertException(true).then(function(){"
                + "location.replace('" + safeUri + "');"
                + "});}"
                + "function cancel(){history.back();}"
                + "</script></div></body></html>";
        return "data:text/html;charset=utf-8," + encodeForDataUri(html);
    }

    private static String encodeForDataUri(String content) {
        return URLEncoder.encode(content, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("</", "<\\/");
    }
}
