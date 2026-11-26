package com.spoongecko.browser;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * [PDF] PDF viewing and saving.
 *
 *  - Viewing: GeckoView ships Mozilla's PDF viewer (pdf.js) but with it switched OFF, so a PDF
 *    link either did nothing useful or went straight to a download. {@link #enableViewer()}
 *    switches it on; the viewer then opens PDFs inside the page, with its own zoom, search and
 *    Download button (that button reaches the app's downloader like any other download).
 *  - Saving: a PDF address is downloaded as the original file; any other page can be saved as a
 *    PDF ("Save page as PDF"). Both go through DownloadEngine, so they get the same notification,
 *    progress and Downloads folder as every other download.
 */
final class PdfSupport {

    private static final String TAG = "SpoonGecko";
    private static final int MAX_TITLE_NAME = 80;

    private PdfSupport() { }

    // ------------------------------------------------------------------
    // Viewer
    // ------------------------------------------------------------------

    /**
     * Turns on GeckoView's PDF viewer. The preference is stored in the profile, so it is set on
     * every start (cheap, and it also repairs a profile where it was changed). Needs the runtime to
     * exist. If the viewer does not appear the very first time after an update, close the app once
     * and open it again: Gecko registers the viewer when it starts.
     */
    static void enableViewer() {
        setViewerEnabled(true);
    }

    static void enableViewer(android.content.Context context) {
        setViewerEnabled(BrowserSettings.isPdfViewerEnabled(context));
    }

    static void setViewerEnabled(boolean enabled) {
        try {
            GeckoPreferenceController.setGeckoPref(
                    "pdfjs.disabled", !enabled, GeckoPreferenceController.PREF_BRANCH_USER)
                    .exceptionally(e -> {
                        Log.w(TAG, "could not update the PDF viewer setting", e);
                        return null;
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "could not update the PDF viewer setting", e);
        }
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    /** http(s) address whose last path segment ends in ".pdf" (query and fragment ignored). */
    static boolean looksLikePdfUrl(String url) {
        final String seg = lastPathSegment(url);
        return seg != null && seg.toLowerCase(Locale.ROOT).endsWith(".pdf") && seg.length() > 4;
    }

    /**
     * The name a saved PDF gets: the file's own name for a PDF address; otherwise the page title
     * (or the site's name when there is no title) plus ".pdf". Always ends in ".pdf".
     */
    static String fileNameFor(String title, String url) {
        final String fromUrl = lastPathSegment(url);
        if (fromUrl != null && looksLikePdfUrl(url)) return cleanName(fromUrl);
        String base = title == null ? "" : title.trim();
        if (base.isEmpty()) base = hostOf(url);
        if (base.isEmpty()) base = "page";
        base = cleanName(base);
        if (base.toLowerCase(Locale.ROOT).endsWith(".pdf")) return base;
        if (base.length() > MAX_TITLE_NAME) base = base.substring(0, MAX_TITLE_NAME).trim();
        return base + ".pdf";
    }

    /**
     * A Content-Disposition value that carries any file name unharmed (RFC 5987), so titles in
     * every language keep their letters. DownloadNames reads this form.
     */
    static String contentDisposition(String fileName) {
        final byte[] bytes = fileName.getBytes(StandardCharsets.UTF_8);
        final StringBuilder b = new StringBuilder("attachment; filename*=UTF-8''");
        for (byte x : bytes) {
            final int c = x & 0xff;
            final boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '~';
            if (plain) b.append((char) c);
            else b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
        return b.toString();
    }

    private static String cleanName(String raw) {
        final StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || "\\/:*?\"<>|".indexOf(c) >= 0) b.append('_');
            else b.append(c);
        }
        String s = b.toString().replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < s.length() && s.charAt(start) == '.') start++;
        s = s.substring(start).trim();
        return s.isEmpty() ? "page" : s;
    }

    /** Percent-decoded last path segment of an http(s) address; null if there is none. */
    private static String lastPathSegment(String url) {
        if (url == null) return null;
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null;
        int cut = url.length();
        final int q = url.indexOf('?');
        final int h = url.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        final String base = url.substring(0, cut);
        final int slash = base.indexOf('/', base.indexOf("://") + 3);
        if (slash < 0) return null;
        String seg = base.substring(base.lastIndexOf('/') + 1);
        final int semi = seg.indexOf(';');
        if (semi >= 0) seg = seg.substring(0, semi);                 // ;jsessionid=...
        if (seg.isEmpty()) return null;
        return new String(percentDecode(seg), StandardCharsets.UTF_8);
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return "";
        String rest = url.substring(url.indexOf("://") + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        rest = rest.substring(0, end);
        final int at = rest.lastIndexOf('@');
        if (at >= 0) rest = rest.substring(at + 1);
        return rest.toLowerCase(Locale.ROOT).startsWith("www.") ? rest.substring(4) : rest;
    }

    private static byte[] percentDecode(String s) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                out.write((Character.digit(s.charAt(i + 1), 16) << 4)
                        | Character.digit(s.charAt(i + 2), 16));
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

    // ------------------------------------------------------------------
    // Saving a page as a PDF
    // ------------------------------------------------------------------

    /**
     * Prints the page in {@code session} to a PDF and saves it as a download. Must be called on the
     * UI thread. The result is read by DownloadEngine, which shows the progress notification.
     */
    static void savePageAsPdf(final Activity activity, final GeckoRuntime runtime,
                              final GeckoSession session, final String title, final String url) {
        final String name = fileNameFor(title, url);
        try {
            final GeckoResult<?> pdf = session.saveAsPdf();
            pdf.accept(value -> activity.runOnUiThread(
                            () -> startDownload(activity, runtime, value, name, url)))
                    .exceptionally(e -> {
                        Log.w(TAG, "saving the page as PDF failed", e);
                        activity.runOnUiThread(() -> toast(activity, "Couldn't save the page as PDF"));
                        return null;
                    });
            toast(activity, "Saving PDF\u2026");
        } catch (RuntimeException e) {
            Log.w(TAG, "saving the page as PDF could not start", e);
            toast(activity, "Couldn't save the page as PDF");
        }
    }

    private static void startDownload(Activity activity, GeckoRuntime runtime, Object value,
                                      String name, String url) {
        try {
            final DownloadRequest request;
            if (value instanceof InputStream) {
                request = new DownloadRequest(url, null, "application/pdf",
                        contentDisposition(name), -1, (InputStream) value, null);
            } else if (value instanceof WebResponse) {
                request = DownloadEngine.requestFromResponse((WebResponse) value, null);
            } else {
                toast(activity, "Couldn't save the page as PDF");
                return;
            }
            DownloadEngine.get(activity).enqueue(runtime, request);
        } catch (RuntimeException e) {
            Log.w(TAG, "the PDF could not be handed to the downloader", e);
            toast(activity, "Couldn't save the page as PDF");
        }
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
