package com.spoongecko.browser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what a downloaded file is called and what type it is. Pure Java (no
 * Android classes) so it can be tested; the platform's MIME table is passed in
 * through {@link MimeLookup}.
 *
 * Why the type matters as much as the name: the file is stored through
 * MediaStore, which compares the extension with the MIME type and APPENDS an
 * extension when they disagree ("report.csv" stored as text/plain becomes
 * "report.csv.txt"). So the MIME type handed to the store is always derived
 * from the final file name, never taken from the server blindly.
 */
final class DownloadNames {

    private DownloadNames() {}

    interface MimeLookup {
        /** "mp4" -> "video/mp4"; null when unknown. */
        String mimeForExtension(String ext);
        /** "video/mp4" -> "mp4"; null when unknown. */
        String extensionForMime(String mime);
    }

    static final String FALLBACK_NAME = "download";
    static final String GENERIC_MIME = "application/octet-stream";
    private static final int MAX_NAME_LENGTH = 120;

    /** Types servers use when they have no real type to offer. */
    private static final Set<String> GENERIC_MIMES = new HashSet<>(Arrays.asList(
            "application/octet-stream", "binary/octet-stream", "application/force-download",
            "application/x-download", "application/download", "application/unknown",
            "application/x-unknown", "application/x-msdownload"));

    /** URL extensions that name a script, not the file it serves. */
    private static final Set<String> DYNAMIC_EXTENSIONS = new HashSet<>(Arrays.asList(
            "php", "asp", "aspx", "jsp", "jspx", "cgi", "pl", "do", "action"));

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * The file name to save under (already safe to use). Order of preference:
     * Content-Disposition, then the last part of the URL path, then "download";
     * an extension is added from the MIME type when the name has none.
     */
    static String fileName(String url, String contentDisposition, String mimeHint, MimeLookup lookup) {
        final String fromHeader = fromContentDisposition(contentDisposition);
        final boolean headerGiven = fromHeader != null && !fromHeader.trim().isEmpty();
        String name = sanitize(headerGiven ? fromHeader : urlFileName(url));

        final String mime = normalizeMime(mimeHint);
        final boolean usableMime = mime != null && !isGeneric(mime);
        final String known = usableMime && lookup != null ? lookup.extensionForMime(mime) : null;

        String ext = extensionOf(name);
        if (ext.isEmpty()) {
            if (known != null && !known.isEmpty()) name = name + "." + known;
        } else if (!headerGiven && DYNAMIC_EXTENSIONS.contains(ext) && known != null
                && !known.isEmpty() && !known.equals(ext)) {
            // "get.php" that really serves a zip: name it after what it is.
            name = name.substring(0, name.length() - ext.length() - 1) + "." + known;
        }
        return name;
    }

    /**
     * The MIME type to store the file under. Derived from the extension of
     * {@code fileName} whenever that extension is known, so type and name never
     * disagree. Unknown extensions are stored as application/octet-stream
     * (which MediaStore never "corrects").
     */
    static String effectiveMime(String fileName, String mimeHint, MimeLookup lookup) {
        final String ext = extensionOf(fileName);
        if (!ext.isEmpty()) {
            final String m = lookup == null ? null : lookup.mimeForExtension(ext);
            return m != null && !m.isEmpty() ? m : GENERIC_MIME;
        }
        final String mime = normalizeMime(mimeHint);
        return mime != null && !isGeneric(mime) ? mime : GENERIC_MIME;
    }

    /** Lower-cased "type/subtype" without parameters; null if there is none. */
    static String normalizeMime(String contentType) {
        if (contentType == null) return null;
        final int semi = contentType.indexOf(';');
        final String m = (semi < 0 ? contentType : contentType.substring(0, semi))
                .trim().toLowerCase(Locale.ROOT);
        return m.isEmpty() || m.indexOf('/') <= 0 ? null : m;
    }

    static boolean isGeneric(String mime) {
        return mime == null || GENERIC_MIMES.contains(mime);
    }

    /** Lower-case extension without the dot; "" when there is none or it does not look like one. */
    static String extensionOf(String name) {
        if (name == null) return "";
        final int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        final String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.length() > 10) return "";
        for (int i = 0; i < ext.length(); i++) {
            final char c = ext.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return "";
        }
        return ext;
    }

    // ------------------------------------------------------------------
    // Content-Disposition (RFC 6266 / RFC 5987 / RFC 2047)
    // ------------------------------------------------------------------

    /** The raw file name from a Content-Disposition header, or null. filename* wins over filename. */
    static String fromContentDisposition(String header) {
        if (header == null) return null;
        String extended = null;
        String plain = null;
        for (String param : splitParams(header)) {
            final int eq = param.indexOf('=');
            if (eq <= 0) continue;
            final String key = param.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            final String value = param.substring(eq + 1).trim();
            if (key.equals("filename*")) {
                final String d = decodeExtendedValue(value);
                if (d != null && !d.trim().isEmpty()) extended = d;
            } else if (key.equals("filename")) {
                final String d = decodeEncodedWords(unquote(value));
                if (!d.trim().isEmpty()) plain = d;
            }
        }
        return extended != null ? extended : plain;
    }

    /** Splits on ';' outside double quotes (honouring backslash escapes inside them). */
    private static String[] splitParams(String s) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (quoted && c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
            } else if (c == '"') {
                quoted = !quoted;
                cur.append(c);
            } else if (c == ';' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String unquote(String v) {
        if (v.length() >= 1 && v.charAt(0) == '"') {
            final StringBuilder b = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                final char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length()) { b.append(v.charAt(++i)); continue; }
                if (c == '"') break;
                b.append(c);
            }
            return b.toString();
        }
        return v;
    }

    /** charset'language'percent-encoded-value, e.g. UTF-8''%E2%82%AC%20rates.txt */
    private static String decodeExtendedValue(String raw) {
        final String v = unquote(raw);
        final int first = v.indexOf('\'');
        final int second = first < 0 ? -1 : v.indexOf('\'', first + 1);
        Charset cs = StandardCharsets.UTF_8;
        String encoded = v;
        if (first >= 0 && second > first) {
            final String name = v.substring(0, first).trim();
            try { if (!name.isEmpty()) cs = Charset.forName(name); } catch (Exception ignored) { /* keep UTF-8 */ }
            encoded = v.substring(second + 1);
        }
        return new String(percentDecodeBytes(encoded), cs);
    }

    private static final Pattern ENCODED_WORD =
            Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");

    /** RFC 2047 "=?UTF-8?B?...?=" words, which some servers put in plain filename=. */
    private static String decodeEncodedWords(String s) {
        if (!s.contains("=?")) return s;
        final String joined = s.replaceAll("(\\?=)\\s+(=\\?)", "$1$2");   // whitespace between words is dropped
        final Matcher m = ENCODED_WORD.matcher(joined);
        final StringBuffer out = new StringBuffer();
        while (m.find()) {
            String decoded;
            try {
                Charset cs = StandardCharsets.UTF_8;
                try { cs = Charset.forName(m.group(1)); } catch (Exception ignored) { /* keep UTF-8 */ }
                final byte[] bytes;
                if (m.group(2).equalsIgnoreCase("B")) {
                    bytes = java.util.Base64.getDecoder().decode(m.group(3));
                } else {
                    bytes = percentDecodeBytes(m.group(3).replace('_', ' ').replace('=', '%'));
                }
                decoded = new String(bytes, cs);
            } catch (Exception e) {
                decoded = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // URL and file-name handling
    // ------------------------------------------------------------------

    /** Last path segment of an http(s)/blob URL, percent-decoded; null if there is none. */
    static String urlFileName(String url) {
        if (url == null) return null;
        final String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) return null;
        int cut = url.length();
        final int q = url.indexOf('?');
        final int h = url.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        final String base = url.substring(0, cut);
        final int scheme = base.indexOf("://");
        String path = base;
        if (scheme >= 0) {
            final int slash = base.indexOf('/', scheme + 3);
            path = slash < 0 ? "" : base.substring(slash);
        }
        String seg = path.substring(path.lastIndexOf('/') + 1);
        final int semi = seg.indexOf(';');
        if (semi >= 0) seg = seg.substring(0, semi);          // ;jsessionid=...
        if (seg.isEmpty()) return null;
        return new String(percentDecodeBytes(seg), StandardCharsets.UTF_8);
    }

    /**
     * Makes any string safe to use as one file name: no directories (so "../x"
     * cannot escape), no characters Android or other file systems reject, no
     * leading dots (hidden / dot-segments), bounded length, never empty.
     */
    static String sanitize(String raw) {
        if (raw == null) return FALLBACK_NAME;
        String s = raw;
        final int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1);
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || "\\/:*?\"<>|".indexOf(c) >= 0) b.append('_');
            else b.append(c);
        }
        s = b.toString().replaceAll("\\s+", " ");
        int start = 0;
        while (start < s.length() && (s.charAt(start) == '.' || s.charAt(start) == ' ')) start++;
        int end = s.length();
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        s = s.substring(start, end);
        if (s.length() > MAX_NAME_LENGTH) {
            final String ext = extensionOf(s);
            final String tail = ext.isEmpty() ? "" : "." + ext;
            s = s.substring(0, MAX_NAME_LENGTH - tail.length()) + tail;
        }
        return s.isEmpty() ? FALLBACK_NAME : s;
    }

    /** Percent-decoding to raw bytes; text outside %XX is kept as UTF-8, bad escapes are kept literally. */
    static byte[] percentDecodeBytes(String s) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write((Character.digit(s.charAt(i + 1), 16) << 4) | Character.digit(s.charAt(i + 2), 16));
                i += 3;
            } else {
                final int cp = s.codePointAt(i);
                final byte[] enc = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.write(enc, 0, enc.length);
                i += Character.charCount(cp);
            }
        }
        return out.toByteArray();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
