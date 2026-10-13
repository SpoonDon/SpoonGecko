package com.spoongecko.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.mozilla.geckoview.WebResponse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles downloads delivered by GeckoView's
 * ContentDelegate.onExternalResponse. Streams the WebResponse body to
 * a file under the app's private external Downloads directory, updating
 * progress on the main thread.
 *
 * Files land at:
 *   /sdcard/Android/data/com.spoongecko.browser/files/Downloads/
 *
 * No storage permission is required on any supported API level (26+).
 */
public final class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_THROTTLE_MS = 100L;

    private static DownloadManager sInstance;

    public interface Listener {
        void onDownloadsChanged();
        void onDownloadComplete(Download d);
        void onDownloadFailed(Download d, String error);
    }

    private final Context appContext;
    private final File downloadsDir;
    private final List<Download> downloads =
            Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong nextId = new AtomicLong(1L);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Listener listener;

    private DownloadManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(appContext.getFilesDir(), "Downloads");
        if (!base.exists()) base.mkdirs();
        this.downloadsDir = base;
        Log.i(TAG, "downloads dir: " + downloadsDir.getAbsolutePath());
    }

    public static synchronized DownloadManager getInstance(Context ctx) {
        if (sInstance == null) sInstance = new DownloadManager(ctx);
        return sInstance;
    }

    public void setListener(Listener l) { this.listener = l; }

    public File getDownloadsDir() { return downloadsDir; }

    /**
     * Used by DownloadsDialog to detach its listener when the dialog closes.
     * Prevents the dialog's adapter from being updated after dismissal.
     */
    public boolean hasListener(Listener l) {
        return listener == l;
    }

    public List<Download> getDownloads() {
        synchronized (downloads) {
            return new ArrayList<>(downloads);
        }
    }

    // -------------------------------------------------------------- enqueue

    /**
     * Called from TabManager.ContentDelegate.onExternalResponse.
     * Must be invoked on the UI thread (GeckoView delivers it there).
     */
    public void enqueue(WebResponse response) {
        if (response == null) return;

        String url = response.uri == null ? "" : response.uri;
        String mime = headerFirst(response.headers, "content-type");
        String filename = pickFilename(response, url);
        File target = uniqueFile(downloadsDir, filename);

        final Download d = new Download(
                nextId.getAndIncrement(), url, target.getName(), mime, target);

        // Content-Length -> total size for progress
        String len = headerFirst(response.headers, "content-length");
        if (len != null) {
            try { d.totalBytes = Long.parseLong(len.trim()); }
            catch (NumberFormatException ignored) {}
        }

        downloads.add(d);
        d.state = Download.State.RUNNING;
        notifyChanged();

        final InputStream body = response.body;
        if (body == null) {
            d.state = Download.State.FAILED;
            d.error = "empty response body";
            notifyChanged();
            notifyFailed(d, d.error);
            return;
        }

        executor.execute(() -> streamToFile(d, body));
    }

    // -------------------------------------------------------------- worker

    private void streamToFile(Download d, InputStream rawBody) {
        OutputStream out = null;
        InputStream in = null;
        long lastProgress = 0L;
        try {
            in = rawBody instanceof BufferedInputStream
                    ? rawBody : new BufferedInputStream(rawBody, BUFFER_SIZE);
            out = new BufferedOutputStream(
                    new FileOutputStream(d.targetFile), BUFFER_SIZE);

            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (d.cancelRequested) {
                    d.state = Download.State.CANCELED;
                    break;
                }
                out.write(buf, 0, n);
                d.bytesRead += n;

                long now = System.currentTimeMillis();
                if (now - lastProgress >= PROGRESS_THROTTLE_MS) {
                    lastProgress = now;
                    notifyChanged();
                }
            }
            out.flush();

            if (d.state != Download.State.CANCELED) {
                d.state = Download.State.COMPLETE;
            }
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + d.url, e);
            d.state = Download.State.FAILED;
            d.error = e.getMessage() == null ? e.getClass().getSimpleName()
                                             : e.getMessage();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }

        if (d.state == Download.State.CANCELED
                || d.state == Download.State.FAILED) {
            // best-effort cleanup of partial file
            try { if (d.targetFile.exists()) d.targetFile.delete(); }
            catch (Exception ignored) {}
        }

        if (d.state == Download.State.COMPLETE) {
            notifyChanged();
            notifyComplete(d);
        } else if (d.state == Download.State.FAILED) {
            notifyChanged();
            notifyFailed(d, d.error == null ? "unknown" : d.error);
        } else {
            notifyChanged();
        }
    }

    public void cancel(Download d) {
        if (d == null || !d.isActive()) return;
        d.cancelRequested = true;
    }

    public void delete(Download d) {
        if (d == null) return;
        cancel(d);
        try { if (d.targetFile.exists()) d.targetFile.delete(); }
        catch (Exception ignored) {}
        downloads.remove(d);
        notifyChanged();
    }

    public void clearCompleted() {
        synchronized (downloads) {
            for (int i = downloads.size() - 1; i >= 0; i--) {
                Download d = downloads.get(i);
                if (d.state == Download.State.COMPLETE
                        || d.state == Download.State.FAILED
                        || d.state == Download.State.CANCELED) {
                    downloads.remove(i);
                }
            }
        }
        notifyChanged();
    }

    // -------------------------------------------------------------- actions

    public void open(Download d) {
        if (d == null || d.state != Download.State.COMPLETE) return;
        try {
            Uri uri = FileProvider.getUriForFile(
                    appContext,
                    appContext.getPackageName() + ".fileprovider",
                    d.targetFile);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, guessMime(d));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "open failed", e);
        }
    }

    public void share(Download d) {
        if (d == null || d.state != Download.State.COMPLETE) return;
        try {
            Uri uri = FileProvider.getUriForFile(
                    appContext,
                    appContext.getPackageName() + ".fileprovider",
                    d.targetFile);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(guessMime(d));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "Share download");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "share failed", e);
        }
    }

    // -------------------------------------------------------------- helpers

    private void notifyChanged() {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onDownloadsChanged();
        });
    }

    private void notifyComplete(Download d) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onDownloadComplete(d);
        });
    }

    private void notifyFailed(Download d, String msg) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onDownloadFailed(d, msg);
        });
    }

    private static String guessMime(Download d) {
        if (d.mimeType != null && !d.mimeType.isEmpty()
                && d.mimeType.contains("/")) {
            int semi = d.mimeType.indexOf(';');
            return semi > 0 ? d.mimeType.substring(0, semi).trim() : d.mimeType;
        }
        String name = d.filename == null ? "" : d.filename;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static String headerFirst(Map<String, String> headers, String key) {
        if (headers == null || key == null) return null;
        // WebResponse uses a case-insensitive map, but be defensive anyway
        String v = headers.get(key);
        if (v != null) return v;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static final Pattern CD_FILENAME_STAR = Pattern.compile(
            "filename\\*\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_FILENAME = Pattern.compile(
            "filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private static String pickFilename(WebResponse response, String url) {
        String cd = headerFirst(response.headers, "content-disposition");
        String candidate = null;
        if (cd != null) {
            Matcher m = CD_FILENAME_STAR.matcher(cd);
            if (m.find()) candidate = decodeRfc5987(m.group(1).trim());
            if (candidate == null || candidate.isEmpty()) {
                m = CD_FILENAME.matcher(cd);
                if (m.find()) candidate = m.group(1).trim();
            }
        }
        if (candidate == null || candidate.isEmpty()) {
            candidate = basenameFromUrl(url);
        }
        candidate = sanitize(candidate);
        if (candidate.isEmpty()) candidate = "download";
        return candidate;
    }

    private static String decodeRfc5987(String value) {
        // Format: UTF-8''file%20name.ext  (we ignore charset variants)
        try {
            int q1 = value.indexOf("''");
            String encoded = q1 >= 0 ? value.substring(q1 + 2) : value;
            return java.net.URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String basenameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String path = Uri.parse(url).getLastPathSegment();
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        // strip query strings and any path bits
        int q = name.indexOf('?');   if (q >= 0) name = name.substring(0, q);
        int h = name.indexOf('#');   if (h >= 0) name = name.substring(0, h);
        name = name.replace('/', '_').replace('\\', '_');
        // remove control chars
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        name = sb.toString().trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 180) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 10)
                    ? name.substring(dot) : "";
            name = name.substring(0, 180 - ext.length()) + ext;
        }
        return name;
    }

    private static File uniqueFile(File dir, String filename) {
        File f = new File(dir, filename);
        if (!f.exists()) return f;
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String ext  = dot > 0 ? filename.substring(dot)   : "";
        for (int i = 1; i < 10000; i++) {
            File g = new File(dir, stem + " (" + i + ")" + ext);
            if (!g.exists()) return g;
        }
        return new File(dir, stem + " (" + System.currentTimeMillis() + ")" + ext);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
