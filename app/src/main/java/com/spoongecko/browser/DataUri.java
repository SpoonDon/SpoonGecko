package com.spoongecko.browser;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Decoder for data: URIs ("data:image/png;base64,....").
 *
 * Needed because such an address can't be fetched over the network: a page
 * that shows an inline image or generates a file client-side hands the browser
 * the bytes inside the URL itself.
 */
final class DataUri {

    private DataUri() {}

    static final class Parsed {
        final String mime;
        final byte[] data;

        Parsed(String mime, byte[] data) {
            this.mime = mime;
            this.data = data;
        }
    }

    static boolean isDataUri(String uri) {
        return uri != null && uri.regionMatches(true, 0, "data:", 0, 5);
    }

    /** @return the media type and the bytes, or null if this is not a well-formed data: URI. */
    static Parsed parse(String uri) {
        if (!isDataUri(uri)) return null;
        final int comma = uri.indexOf(',');
        if (comma < 0) return null;
        final String meta = uri.substring(5, comma);
        final String payload = uri.substring(comma + 1);

        boolean base64 = false;
        String mime = null;
        final String[] parts = meta.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            final String p = parts[i].trim();
            if (i == 0) {
                if (p.indexOf('/') > 0) mime = p.toLowerCase(Locale.ROOT);
            } else if (p.equalsIgnoreCase("base64")) {
                base64 = true;
            }
        }
        if (mime == null) mime = "text/plain";

        try {
            final byte[] data;
            if (base64) {
                // Percent-escapes can appear inside (e.g. %3D for '='); undo them first.
                // Whitespace is allowed inside; anything else outside the alphabet is an error. (The MIME
                // decoder would silently skip such characters and "succeed" with truncated data.)
                final String clean = new String(DownloadNames.percentDecodeBytes(payload), StandardCharsets.ISO_8859_1)
                        .replaceAll("\\s+", "");
                data = java.util.Base64.getDecoder().decode(clean);
            } else {
                data = DownloadNames.percentDecodeBytes(payload);
            }
            return new Parsed(mime, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
