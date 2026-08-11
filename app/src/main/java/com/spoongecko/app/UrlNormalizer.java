package com.spoongecko.app;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class UrlNormalizer {

    public static String normalize(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        input = input.trim();

        // If it already has a scheme, return as-is
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }

        // If it contains spaces or no dots, treat as a search query
        if (input.contains(" ") || !input.contains(".")) {
            try {
                return "https://duckduckgo.com/?q=" + URLEncoder.encode(input, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return "https://duckduckgo.com/?q=" + input.replace(" ", "+");
            }
        }

        // Extract host to check if it's a local network target
        String host = input.split("/")[0];
        if (isLocalHost(host)) {
            return "http://" + input; // Force HTTP for local LAN
        }

        // Default to HTTPS for public domains
        return "https://" + input;
    }

    // Exposed publicly so MainActivity can use it for the HTTPS->HTTP fallback
    public static boolean isLocalHost(String host) {
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")) return true;
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true;
        // Matches 172.16.0.0 to 172.31.255.255
        if (host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")) return true;
        return false;
    }
}
