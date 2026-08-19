package com.spoongecko.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoWebExecutor;
import org.mozilla.geckoview.WebRequest;
import org.mozilla.geckoview.WebResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadManager {

    private static final ExecutorService FALLBACK_EXECUTOR = Executors.newFixedThreadPool(2);

    private DownloadManager() {}

    public static void handleDownload(Context context, WebResponse response) {
        if (context == null || response == null || response.uri == null || response.body == null) return;

        if (DownloadDispatcher.isExternalMode(context)) {
            String filename = deriveFilename(response);
            String mime = deriveMimeType(response, filename);
            DownloadDispatcher.openExternal(context, response.uri, mime, filename);
            closeQuietly(response.body);
            return;
        }

        nativeDownload(context, response);
    }

    public static void handleDownloadNative(Context context, WebResponse response) {
        nativeDownload(context, response);
    }

    public static void downloadUrlNative(Context context, GeckoRuntime runtime, String url) {
        if (context == null || runtime == null || url == null || url.isEmpty()) return;
        try {
            new GeckoWebExecutor(runtime).fetch(new WebRequest.Builder(url).build()).accept(
                    response -> {
                        if (response != null && response.body != null) {
                            nativeDownload(context, response);
                        }
                    },
                    error -> {}
            );
        } catch (Exception ignored) {}
    }

    private static void nativeDownload(Context context, WebResponse response) {
        if (context == null || response == null || response.uri == null || response.body == null) return;
        Context appContext = context.getApplicationContext();
        String filename = deriveFilename(response);
        String mime = deriveMimeType(response, filename);
        long totalBytes = parseContentLength(response);
        InputStream in = response.body;
        try {
            DownloadService.enqueue(appContext, filename, mime, totalBytes, in);
        } catch (Exception e) {
            FALLBACK_EXECUTOR.execute(() -> {
                saveToDownloads(appContext, in, filename, mime);
                closeQuietly(in);
            });
        }
    }

    private static long parseContentLength(WebResponse response) {
        if (response.headers == null) return -1;
        String value = getHeaderIgnoreCase(response.headers, "content-length");
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void saveToDownloads(Context context, InputStream in, String filename, String mime) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("insert returned null");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("openOutputStream returned null");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (IOException e) {
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String deriveFilename(WebResponse response) {
        if (response.headers != null) {
            String contentDisposition = getHeaderIgnoreCase(response.headers, "content-disposition");
            if (contentDisposition != null) {
                String filename = extractFilename(contentDisposition);
                if (filename != null && !filename.isEmpty()) return sanitize(filename);
            }
        }
        String path = Uri.parse(response.uri).getLastPathSegment();
        if (path != null && !path.isEmpty()) return sanitize(path);
        return "download";
    }

    private static String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static String extractFilename(String contentDisposition) {
        if (contentDisposition == null) return null;
        String lower = contentDisposition.toLowerCase(Locale.ROOT);
        int starIdx = lower.indexOf("filename*=utf-8''");
        if (starIdx != -1) {
            String rest = contentDisposition.substring(starIdx + "filename*=utf-8''".length()).trim();
            if (rest.startsWith("\"")) {
                int end = rest.indexOf('"', 1);
                if (end != -1) {
                    rest = rest.substring(1, end);
                } else {
                    rest = rest.substring(1).replace("\"", "");
                }
            } else {
                int semi = rest.indexOf(';');
                if (semi != -1) rest = rest.substring(0, semi).trim();
            }
            if (!rest.isEmpty()) {
                try {
                    return URLDecoder.decode(rest, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {}
            }
        }
        int idx = lower.indexOf("filename=");
        if (idx == -1) return null;
        String rest = contentDisposition.substring(idx + "filename=".length()).trim();
        if (rest.isEmpty()) return null;
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            if (end != -1) return rest.substring(1, end);
            return rest.substring(1).replace("\"", "");
        }
        int semi = rest.indexOf(';');
        if (semi != -1) rest = rest.substring(0, semi).trim();
        return rest;
    }

    private static String sanitize(String name) {
        if (name == null) return "download";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) return "download";
        return cleaned;
    }

    private static String deriveMimeType(WebResponse response, String filename) {
        if (response.headers != null) {
            String contentType = getHeaderIgnoreCase(response.headers, "content-type");
            if (contentType != null && !contentType.isEmpty()) {
                int semi = contentType.indexOf(';');
                if (semi != -1) contentType = contentType.substring(0, semi).trim();
                return contentType;
            }
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot == -1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }
}
