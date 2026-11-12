package com.spoongecko.browser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which navigations should download a file instead of opening it.
 *
 * GeckoView only hands the app a download when the SERVER marks the response
 * as an attachment or the type is one it can't show. A direct link to
 * "song.mp3", "notes.txt" or "movie.mp4" is neither: Gecko happily opens it in
 * its text viewer or media player. There is no GeckoView hook that sees the
 * response type before it is shown, so such links are recognised from the URL
 * instead, at the moment the user follows them.
 *
 * The rule is intentionally narrow, to leave everything that already works
 * alone:
 *   - only navigations started by a PAGE (a tapped link, a script). Anything
 *     the user typed, picked from history or opened explicitly stays as it is,
 *     so a file can still be viewed on purpose;
 *   - only http(s) URLs whose last path segment ends in a known FILE extension;
 *   - PDFs and images are NOT in the list: Gecko's PDF viewer and image viewer
 *     are what people expect for those. (Long-press -> Download still saves
 *     them.)
 */
final class DownloadPolicy {

    private DownloadPolicy() {}

    /** Master switch: set to false to restore the old behaviour for plain links. */
    static final boolean AUTO_DOWNLOAD_LINKS = true;

    private static final Set<String> FILE_EXTENSIONS = new HashSet<>(Arrays.asList(
            // audio
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "wma", "aiff", "aif",
            "mid", "midi", "amr",
            // video
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "3gp", "3g2", "mpg", "mpeg",
            "ogv", "vob",
            // text-based files that otherwise open in the text viewer
            "txt", "csv", "tsv", "log", "md", "json", "xml", "yaml", "yml", "ini", "cfg", "conf",
            "srt", "vtt", "sql", "rtf", "tex", "bib", "ics", "vcf",
            // documents and e-books
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "mobi",
            "azw3",
            // archives, installers, images of disks, fonts
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "apk", "apks", "xapk",
            "aab", "exe", "msi", "dmg", "pkg", "iso", "img", "bin", "deb", "rpm", "jar", "whl",
            "ttf", "otf", "torrent"));

    /** Whether this navigation should be turned into a download. */
    static boolean shouldInterceptNavigation(String url, boolean initiatedByPage) {
        return AUTO_DOWNLOAD_LINKS && initiatedByPage && isDownloadLinkUrl(url);
    }

    /** http(s) URL whose last path segment ends in a file extension from the list above. */
    static boolean isDownloadLinkUrl(String url) {
        if (url == null) return false;
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        int cut = url.length();
        final int q = url.indexOf('?');
        final int h = url.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        final String base = url.substring(0, cut);
        final int slash = base.indexOf('/', base.indexOf("://") + 3);
        if (slash < 0) return false;                               // bare host: a page
        String seg = base.substring(base.lastIndexOf('/') + 1);
        final int semi = seg.indexOf(';');
        if (semi >= 0) seg = seg.substring(0, semi);               // ;jsessionid=...
        final String ext = DownloadNames.extensionOf(
                new String(DownloadNames.percentDecodeBytes(seg), java.nio.charset.StandardCharsets.UTF_8));
        return !ext.isEmpty() && FILE_EXTENSIONS.contains(ext);
    }
}
