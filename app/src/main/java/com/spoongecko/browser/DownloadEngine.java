package com.spoongecko.browser;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.text.format.Formatter;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoWebExecutor;
import org.mozilla.geckoview.WebRequest;
import org.mozilla.geckoview.WebResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads: owns the jobs, the files, the notifications, and keeps the
 * process alive (through DownloadService) while anything is transferring.
 *
 * All decisions that can be tested without Android live in DownloadNames,
 * DownloadPolicy, DataUri and DownloadCore; this class connects them to the
 * platform.
 *
 * Why not Android's DownloadManager (what the app used before)? It re-requests
 * the URL on its own, so it has no cookies, no login session, no POST body and
 * can't read blob: or data: URLs; downloads that needed any of those failed.
 * Here the bytes come either from the page's own response (already open and
 * authenticated by Gecko) or from Gecko's network stack, which shares the
 * browser's cookies.
 *
 * Threading: everything except the copy loop runs on the UI thread.
 */
final class DownloadEngine {

    private static final String TAG = "SpoonGecko";

    static final String CHANNEL_ID = "spoongecko_downloads";
    static final String ACTION_CANCEL = "com.spoongecko.browser.action.DOWNLOAD_CANCEL";
    static final String ACTION_RETRY = "com.spoongecko.browser.action.DOWNLOAD_RETRY";
    static final String EXTRA_ID = "download_id";

    private static final int PROGRESS_BASE = 0x6000;
    private static final int DONE_BASE = 0x6800;
    private static final int ID_MASK = 0x7FF;
    private static final int MAX_PARALLEL = 3;
    private static final int MAX_RETRYABLE = 20;
    private static final long WAKE_TIMEOUT_MS = 3L * 60 * 60 * 1000;
    private static final long PROGRESS_INTERVAL_MS = 500;

    private static final DownloadNames.MimeLookup LOOKUP = new DownloadNames.MimeLookup() {
        @Override public String mimeForExtension(String ext) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        @Override public String extensionForMime(String mime) {
            return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        }
    };

    private static DownloadEngine instance;

    static synchronized DownloadEngine get(Context ctx) {
        if (instance == null) instance = new DownloadEngine(ctx.getApplicationContext());
        return instance;
    }

    private static final class Job {
        final int id;
        final DownloadRequest request;
        final GeckoRuntime runtime;
        volatile String name;
        volatile String mime;
        volatile InputStream stream;
        final AtomicBoolean cancelled = new AtomicBoolean();
        long done;
        long total;
        boolean pinned;

        Job(int id, DownloadRequest request, GeckoRuntime runtime) {
            this.id = id;
            this.request = request;
            this.runtime = runtime;
            this.total = request.length;
        }
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NotificationManager nm;
    private final ExecutorService pool;
    private final Map<Integer, Job> jobs = new LinkedHashMap<>();      // UI thread only
    private final Map<Integer, Job> failed = new LinkedHashMap<>();    // retryable failures
    private int nextId = 1;
    private PowerManager.WakeLock wakeLock;

    private DownloadEngine(Context app) {
        this.app = app;
        this.nm = app.getSystemService(NotificationManager.class);
        this.pool = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
            final Thread t = new Thread(r, "spoongecko-download");
            t.setDaemon(true);
            return t;
        });
        final NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress and results of file downloads");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    // ------------------------------------------------------------------
    // Entry points (UI thread)
    // ------------------------------------------------------------------

    /** Builds a request from a response GeckoView could not show (a download the page started). */
    static DownloadRequest requestFromResponse(WebResponse r, Runnable onStreamDone) {
        final String encoding = header(r.headers, "content-encoding");
        long length = -1;
        // With a Content-Encoding the announced length is of the compressed bytes: not comparable.
        if (encoding == null || encoding.trim().isEmpty() || encoding.trim().equalsIgnoreCase("identity")) {
            length = parseLong(header(r.headers, "content-length"));
        }
        return new DownloadRequest(r.uri, null, header(r.headers, "content-type"),
                header(r.headers, "content-disposition"), length, r.body, onStreamDone);
    }

    void enqueue(GeckoRuntime runtime, DownloadRequest request) {
        final Job j = new Job(nextId++, request, runtime);
        j.name = DownloadNames.fileName(request.url, request.dispositionHint, request.mimeHint, LOOKUP);
        j.mime = DownloadNames.effectiveMime(j.name, request.mimeHint, LOOKUP);
        j.stream = request.stream;
        jobs.put(j.id, j);

        toast("Downloading " + j.name);
        postProgress(j);
        pinIfNeeded(j);
        updateWakeLock();
        start(j);
    }

    boolean hasActiveJobs() {
        return !jobs.isEmpty();
    }

    void cancel(int id) {
        final Job j = jobs.get(id);
        if (j == null) return;
        j.cancelled.set(true);
        // A read that is waiting for data would otherwise hold on until it times out.
        DownloadCore.closeQuietly(j.stream);
        if (j.stream == null) finishCancelled(j);   // still waiting for the response: nothing to interrupt
    }

    void retry(int id) {
        final Job j = failed.remove(id);
        nm.cancel(DONE_BASE + (id & ID_MASK));
        if (j != null) enqueue(j.runtime, j.request);
    }

    /** After a crash or kill, progress notifications from the previous run would stay forever: remove them. */
    void clearStaleNotifications() {
        if (!jobs.isEmpty()) return;     // the process is alive and downloading: they are not stale
        try {
            for (StatusBarNotification n : nm.getActiveNotifications()) {
                final int id = n.getId();
                if (id >= PROGRESS_BASE && id <= PROGRESS_BASE + ID_MASK) nm.cancel(id);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "clearing stale download notifications failed", e);
        }
    }

    /** Notification for DownloadService when it was started but nothing is left to show. */
    Notification placeholderNotification() {
        return new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloads")
                .build();
    }

    // ------------------------------------------------------------------
    // Starting a job
    // ------------------------------------------------------------------

    private void start(final Job j) {
        final DownloadRequest r = j.request;
        if (r.stream != null) {
            submit(j, r.stream, r.length);
        } else if (DataUri.isDataUri(r.url)) {
            pool.execute(() -> {
                final DataUri.Parsed p = DataUri.parse(r.url);
                if (p == null) {
                    main.post(() -> fail(j, "The data in this address is not valid"));
                    return;
                }
                j.name = DownloadNames.fileName(r.url, null, p.mime, LOOKUP);
                j.mime = DownloadNames.effectiveMime(j.name, p.mime, LOOKUP);
                j.total = p.data.length;
                work(j, new ByteArrayInputStream(p.data), p.data.length);
            });
        } else {
            fetch(j);
        }
    }

    /** Address mode: fetch through Gecko's network stack, so the browser's cookies and login apply. */
    private void fetch(final Job j) {
        try {
            final WebRequest.Builder b = new WebRequest.Builder(j.request.url);
            if (j.request.referrer != null && !j.request.referrer.isEmpty()) b.referrer(j.request.referrer);
            new GeckoWebExecutor(j.runtime).fetch(b.build()).accept(
                    resp -> {
                        if (resp == null) { fail(j, "No response"); return; }
                        if (j.cancelled.get()) { DownloadCore.closeQuietly(resp.body); finishCancelled(j); return; }
                        if (resp.statusCode >= 400) {
                            DownloadCore.closeQuietly(resp.body);
                            fail(j, "The server answered HTTP " + resp.statusCode);
                            return;
                        }
                        if (resp.body == null) { fail(j, "The server sent no data"); return; }
                        final String encoding = header(resp.headers, "content-encoding");
                        final long length = encoding == null || encoding.trim().isEmpty()
                                || encoding.trim().equalsIgnoreCase("identity")
                                ? parseLong(header(resp.headers, "content-length")) : -1;
                        final String mimeHint = header(resp.headers, "content-type");
                        j.name = DownloadNames.fileName(resp.uri != null ? resp.uri : j.request.url,
                                header(resp.headers, "content-disposition"), mimeHint, LOOKUP);
                        j.mime = DownloadNames.effectiveMime(j.name, mimeHint, LOOKUP);
                        j.total = length;
                        j.stream = resp.body;
                        try { resp.setReadTimeoutMillis(120_000); } catch (RuntimeException ignored) { /* keep the default */ }
                        postProgress(j);
                        submit(j, resp.body, length);
                    },
                    error -> fail(j, describe(error)));
        } catch (RuntimeException e) {
            fail(j, describe(e));
        }
    }

    private void submit(final Job j, final InputStream in, final long total) {
        pool.execute(() -> work(j, in, total));
    }

    // ------------------------------------------------------------------
    // The transfer (worker thread)
    // ------------------------------------------------------------------

    private void work(final Job j, final InputStream in, final long total) {
        final AndroidSink sink;
        try {
            sink = createSink(j.name, j.mime);
        } catch (RuntimeException e) {
            DownloadCore.closeQuietly(in);
            main.post(() -> fail(j, describe(e)));
            postStreamDone(j);
            return;
        }
        try {
            final DownloadCore.Result r = DownloadCore.run(in, sink, total, j.cancelled,
                    new DownloadCore.Throttle(PROGRESS_INTERVAL_MS, System::currentTimeMillis),
                    (done, tot) -> main.post(() -> onProgress(j, done, tot)));
            main.post(() -> finishOk(j, r.finalName, sink.viewUri()));
        } catch (DownloadCore.CancelledException e) {
            main.post(() -> finishCancelled(j));
        } catch (IOException | RuntimeException e) {
            if (j.cancelled.get()) main.post(() -> finishCancelled(j));   // the stream was closed on purpose
            else main.post(() -> fail(j, describe(e)));
        } finally {
            postStreamDone(j);
        }
    }

    private void postStreamDone(Job j) {
        final Runnable done = j.request.onStreamDone;
        if (done != null) main.post(done);
    }

    // ------------------------------------------------------------------
    // Outcomes (UI thread)
    // ------------------------------------------------------------------

    private void onProgress(Job j, long done, long total) {
        if (!jobs.containsKey(j.id)) return;
        j.done = done;
        if (total > 0) j.total = total;
        postProgress(j);
    }

    private void finishOk(Job j, String finalName, Uri uri) {
        if (jobs.remove(j.id) == null) return;
        leave(j);
        final String shown = finalName == null || finalName.isEmpty() ? j.name : finalName;
        nm.notify(DONE_BASE + (j.id & ID_MASK), new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(shown)
                .setContentText("Download complete")
                .setAutoCancel(true)
                .setContentIntent(openPending(j, uri))
                .build());
        toast("Downloaded " + shown);
    }

    private void fail(Job j, String reason) {
        if (jobs.remove(j.id) == null) return;
        leave(j);
        if (j.request.isRetryable()) {
            failed.put(j.id, j);
            while (failed.size() > MAX_RETRYABLE) failed.remove(failed.keySet().iterator().next());
        }
        final Notification.Builder b = new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(j.name + " \u2013 " + reason)
                .setAutoCancel(true);
        if (j.request.isRetryable()) b.addAction(action(android.R.drawable.ic_popup_sync, "Retry", ACTION_RETRY, j));
        nm.notify(DONE_BASE + (j.id & ID_MASK), b.build());
        toast("Download failed: " + reason);
    }

    private void finishCancelled(Job j) {
        if (jobs.remove(j.id) == null) return;
        leave(j);
        toast("Download cancelled");
    }

    /** Common tail of every outcome: free the foreground slot, remove the progress notification, update locks. */
    private void leave(Job j) {
        if (j.pinned) {
            final Job next = firstActive();
            if (next != null) pin(next);          // switching the foreground notification first avoids a gap
            else DownloadService.release();
        }
        nm.cancel(PROGRESS_BASE + (j.id & ID_MASK));
        updateWakeLock();
    }

    // ------------------------------------------------------------------
    // Foreground service and wake lock
    // ------------------------------------------------------------------

    private void pinIfNeeded(Job j) {
        for (Job other : jobs.values()) if (other.pinned) return;
        pin(j);
    }

    private void pin(Job j) {
        j.pinned = true;
        DownloadService.pin(app, PROGRESS_BASE + (j.id & ID_MASK), buildProgress(j));
    }

    private Job firstActive() {
        for (Job j : jobs.values()) return j;
        return null;
    }

    private void updateWakeLock() {
        try {
            if (jobs.isEmpty()) {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            } else {
                if (wakeLock == null) {
                    final PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpoonGecko:download");
                        wakeLock.setReferenceCounted(false);
                    }
                }
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(WAKE_TIMEOUT_MS);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "download wake lock failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void postProgress(Job j) {
        nm.notify(PROGRESS_BASE + (j.id & ID_MASK), buildProgress(j));
    }

    private Notification buildProgress(Job j) {
        final boolean known = j.total > 0;
        final int percent = known ? (int) Math.min(100, j.done * 100 / j.total) : 0;
        final String text = known
                ? percent + "% \u00b7 " + Formatter.formatShortFileSize(app, j.done)
                    + " of " + Formatter.formatShortFileSize(app, j.total)
                : (j.done > 0 ? Formatter.formatShortFileSize(app, j.done) : "Starting\u2026");
        final Notification.Builder b = new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(j.name)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(known ? 100 : 0, percent, !known)
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", ACTION_CANCEL, j));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private Notification.Action action(int iconRes, String title, String action, Job j) {
        final Intent i = new Intent(app, DownloadService.class).setAction(action).putExtra(EXTRA_ID, j.id);
        final PendingIntent pi = PendingIntent.getService(app, j.id * 4 + (action.equals(ACTION_CANCEL) ? 1 : 2), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(app, iconRes), title, pi).build();
    }

    /** Tapping "Download complete" opens the file; with no way to open it, the system Downloads list. */
    private PendingIntent openPending(Job j, Uri uri) {
        final Intent open;
        if (uri != null) {
            open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, j.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            open = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return PendingIntent.getActivity(app, DONE_BASE + (j.id & ID_MASK), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void toast(String message) {
        try {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "toast failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private AndroidSink createSink(String name, String mime) {
        if (Build.VERSION.SDK_INT >= 29) return new MediaStoreSink(app.getContentResolver(), name, mime);
        File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(app.getFilesDir(), "downloads");
        return new LegacySink(dir, name);
    }

    private abstract static class AndroidSink implements DownloadCore.Sink {
        /** Content URI of the finished file, or null. */
        abstract Uri viewUri();
    }

    /**
     * Android 10+: the public Downloads folder through MediaStore. No storage
     * permission is needed, the file appears in the system Downloads list, and
     * a name that is already taken is made unique ("name (1).ext") by the system.
     */
    @android.annotation.TargetApi(29)
    private static final class MediaStoreSink extends AndroidSink {
        private final ContentResolver resolver;
        private final String name;
        private final String mime;
        private Uri uri;

        MediaStoreSink(ContentResolver resolver, String name, String mime) {
            this.resolver = resolver;
            this.name = name;
            this.mime = mime;
        }

        @Override public OutputStream open() throws IOException {
            final ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.Downloads.IS_PENDING, 1);          // hidden from other apps until complete
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create the file in Downloads");
            final OutputStream out = resolver.openOutputStream(uri);
            if (out == null) throw new IOException("Could not open the file for writing");
            return out;
        }

        @Override public String commit() {
            final ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, v, null, null);
            try (Cursor c = resolver.query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);   // may differ: made unique by the system
            } catch (RuntimeException ignored) { /* the name is cosmetic */ }
            return name;
        }

        @Override public void abort() {
            if (uri == null) return;
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { /* already gone */ }
        }

        @Override Uri viewUri() {
            return uri;
        }
    }

    /** Android 8-9: the app's own external Downloads folder (needs no permission). */
    private static final class LegacySink extends AndroidSink {
        private final File dir;
        private final String name;
        private File file;

        LegacySink(File dir, String name) {
            this.dir = dir;
            this.name = name;
        }

        @Override public OutputStream open() throws IOException {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the Downloads folder");
            file = uniqueFile(dir, name);
            return new FileOutputStream(file);
        }

        @Override public String commit() {
            return file.getName();
        }

        @Override public void abort() {
            if (file != null) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }

        @Override Uri viewUri() {
            return null;
        }
    }

    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + " (" + System.currentTimeMillis() + ")" + ext);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static String header(Map<String, String> headers, String lowerCaseName) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lowerCaseName)) return e.getValue();
        }
        return null;
    }

    private static long parseLong(String s) {
        if (s == null) return -1;
        try {
            final long v = Long.parseLong(s.trim());
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String describe(Throwable t) {
        final String m = t == null ? null : t.getMessage();
        if (m != null && !m.trim().isEmpty()) return m.trim();
        return t == null ? "unknown error" : t.getClass().getSimpleName();
    }
}
