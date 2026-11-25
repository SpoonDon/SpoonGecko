package com.spoongecko.browser;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * [UPLOAD] Pure helper logic for attaching files to a web page: accepted MIME types,
 * visual-media / capture classification, and safe local filenames.
 */
final class UploadFiles {

    static final String FALLBACK_NAME = "file";
    private static final int MAX_NAME_LENGTH = 120;

    static final int CAPTURE_IMAGE = 1;
    static final int CAPTURE_VIDEO = 2;
    static final int CAPTURE_AUDIO = 3;

    private UploadFiles() { }

    /** Looks up the MIME type of a file extension ("pdf" -> "application/pdf"); null if unknown. */
    interface ExtensionResolver {
        String mimeForExtension(String extensionWithoutDot);
    }

    /**
     * Turns what a page asked for ({@code <input accept="...">}) into MIME types the Android
     * picker understands.
     *
     * @return the MIME types to offer, or null for "any file".
     */
    static String[] acceptedTypes(String[] requested, ExtensionResolver resolver) {
        if (requested == null || requested.length == 0) return null;
        final Set<String> out = new LinkedHashSet<>();
        for (String item : requested) {
            if (item == null) continue;
            for (String raw : item.split(",")) {
                String t = raw.trim().toLowerCase(Locale.ROOT);
                final int semi = t.indexOf(';');
                if (semi >= 0) t = t.substring(0, semi).trim();
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
                    if (t.equals("image/jpg")) out.add("image/jpeg");
                }
            }
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    /** True when the page strictly requests image and/or video files (eligible for Photo Picker). */
    static boolean isVisualMediaOnly(String[] mimeTypes) {
        if (mimeTypes == null || mimeTypes.length == 0) return false;
        for (String mime : mimeTypes) {
            if (mime == null) return false;
            final String m = mime.toLowerCase(Locale.ROOT);
            if (!m.startsWith("image/") && !m.startsWith("video/")) return false;
        }
        return true;
    }

    static boolean hasImageTypes(String[] mimeTypes) {
        if (mimeTypes == null) return false;
        for (String mime : mimeTypes) {
            if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        }
        return false;
    }

    static boolean hasVideoTypes(String[] mimeTypes) {
        if (mimeTypes == null) return false;
        for (String mime : mimeTypes) {
            if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) return true;
        }
        return false;
    }

    static boolean hasAudioTypes(String[] mimeTypes) {
        if (mimeTypes == null) return false;
        for (String mime : mimeTypes) {
            if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) return true;
        }
        return false;
    }

    /** Determines whether a capture-enabled input asks for image, video, or audio recording. */
    static int captureCategory(String[] mimeTypes) {
        final boolean img = hasImageTypes(mimeTypes);
        final boolean vid = hasVideoTypes(mimeTypes);
        final boolean aud = hasAudioTypes(mimeTypes);
        if (vid && !img) return CAPTURE_VIDEO;
        if (aud && !img && !vid) return CAPTURE_AUDIO;
        return CAPTURE_IMAGE;
    }

    private static boolean isMimeType(String t) {
        final int slash = t.indexOf('/');
        return slash > 0 && slash < t.length() - 1
                && t.indexOf(' ') < 0 && t.indexOf('/', slash + 1) < 0;
    }

    /**
     * Makes a display name safe as both a local filename and a file:// URI path segment
     * (strips directories, reserved filesystem characters, and URI delimiters like # ? %).
     */
    static String safeFileName(String raw) {
        if (raw == null) return FALLBACK_NAME;
        String s = raw;
        final int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1);
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || "\\/:*?\"<>|#%".indexOf(c) >= 0) b.append('_');
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

    /** Adds ".ext" when the name has no extension of its own. */
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
