package com.spoongecko.browser;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * [UPLOAD] The pure (no Android classes) part of attaching files to a web page: which file types
 * the picker should offer, and what a picked file is called when it is handed to the page.
 * See FilePicker for the rest.
 */
final class UploadFiles {

    static final String FALLBACK_NAME = "file";
    private static final int MAX_NAME_LENGTH = 120;

    private UploadFiles() { }

    /** Looks up the MIME type of a file extension ("pdf" -> "application/pdf"); null if unknown. */
    interface ExtensionResolver {
        String mimeForExtension(String extensionWithoutDot);
    }

    /**
     * Turns what a page asked for ({@code <input accept="...">}) into types the Android file
     * picker understands.
     *
     * GeckoView hands over BOTH MIME types ("image/*") and extensions (".pdf") in one list. The
     * picker only understands MIME types: an extension in its list matches nothing, so a page that
     * says accept=".pdf" made every file unselectable. Extensions are therefore converted to
     * their MIME type.
     *
     * @return the types to offer, or null for "any file". Null is also the answer when a type
     *         cannot be worked out (an extension Android has no MIME type for): hiding the
     *         user's file would be worse than showing all of them, the page checks it anyway.
     */
    static String[] acceptedTypes(String[] requested, ExtensionResolver resolver) {
        if (requested == null || requested.length == 0) return null;
        final Set<String> out = new LinkedHashSet<>();
        for (String raw : requested) {
            if (raw == null) continue;
            String t = raw.trim().toLowerCase(Locale.ROOT);
            final int semi = t.indexOf(';');
            if (semi >= 0) t = t.substring(0, semi).trim();      // "text/plain;charset=utf-8"
            if (t.isEmpty()) continue;
            if (t.equals("*/*") || t.equals("*")) return null;
            if (t.charAt(0) == '.') {
                final String ext = t.substring(1);
                final String mime = ext.isEmpty() || resolver == null
                        ? null : resolver.mimeForExtension(ext);
                if (mime == null || mime.isEmpty()) return null;
                out.add(mime.toLowerCase(Locale.ROOT));
            } else if (isMimeType(t)) {
                out.add(t);
                if (t.equals("image/jpg")) out.add("image/jpeg");   // a common misspelling
            }
            // anything else is not a type at all: ignored
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    private static boolean isMimeType(String t) {
        final int slash = t.indexOf('/');
        return slash > 0 && slash < t.length() - 1
                && t.indexOf(' ') < 0 && t.indexOf('/', slash + 1) < 0;
    }

    /**
     * Makes a display name safe to use as the name of the copy the page receives: no directories
     * (so "../x" cannot escape), none of the characters file systems reject, no leading dots,
     * bounded length (the extension is kept), never empty. The page shows this name, so it should
     * stay as close to the original as possible.
     */
    static String safeFileName(String raw) {
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

    /** Adds ".ext" when the name has no extension of its own (a file the provider gave no name for). */
    static String withExtension(String name, String extensionWithoutDot) {
        if (extensionWithoutDot == null || extensionWithoutDot.isEmpty()) return name;
        return extensionOf(name).isEmpty() ? name + "." + extensionWithoutDot : name;
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
}
