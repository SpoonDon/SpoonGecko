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
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent Download Manager engine: owns queued/active/paused/completed/failed records,
 * staged partial files with HTTP Range resume, notifications, wake lock, and DownloadService.
 */
final class DownloadEngine {

    private static final String TAG = "SpoonGecko";

    static final String CHANNEL_ID = "spoongecko_downloads";
    static final String ACTION_PAUSE = "com.spoongecko.browser.action.DOWNLOAD_PAUSE";
    static final String ACTION_CANCEL = "com.spoongecko.browser.action.DOWNLOAD_CANCEL";
    static final String ACTION_RETRY = "com.spoongecko.browser.action.DOWNLOAD_RETRY";
    static final String EXTRA_ID = "download_id";

    private static final int PROGRESS_BASE = 0x6000;
    private static final int DONE_BASE = 0x6800;
    private static final int ID_MASK = 0x7FF;
    private static final int MAX_PARALLEL = 3;
    private static final long WAKE_TIMEOUT_MS = 3L * 60 * 60 * 1000;
    private static final long PROGRESS_INTERVAL_MS = 500;
    private static final long PERSIST_INTERVAL_MS = 3000;

    interface Listener {
        void onDownloadsChanged();
    }

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

    private static final class ActiveJob {
        final DownloadEntry entry;
        final DownloadRequest request;
        final GeckoRuntime runtime;
        volatile InputStream stream;
        final AtomicBoolean paused = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        boolean pinned;
        boolean resetRangeRetried;
        long lastSpeedTimeMs;
        long lastSpeedBytes;
        long lastPersistMs;

        ActiveJob(DownloadEntry entry, DownloadRequest request, GeckoRuntime runtime) {
            this.entry = entry;
            this.request = request;
            this.runtime = runtime;
            this.stream = request != null ? request.stream : null;
            this.lastSpeedTimeMs = System.currentTimeMillis();
            this.lastSpeedBytes = entry.bytesDone;
            this.lastPersistMs = this.lastSpeedTimeMs;
        }
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NotificationManager nm;
    private final ExecutorService pool;
    private final DownloadStore store;
    private final Map<Long, DownloadEntry> entries = new LinkedHashMap<>();
    private final Map<Long, ActiveJob> activeJobs = new LinkedHashMap<>();
    private final Queue<ActiveJob> waitingQueue = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();

    private long nextId;
    private PowerManager.WakeLock wakeLock;
    private GeckoRuntime cachedRuntime;
    private GeckoWebExecutor cachedExecutor;

    private DownloadEngine(Context app) {
        this.app = app;
        this.nm = app.getSystemService(NotificationManager.class);
        this.store = new DownloadStore(app);

        final DownloadStore.Loaded loaded = store.load();
        this.nextId = loaded.nextId;
        for (DownloadEntry e : loaded.entries) {
            entries.put(e.id, e);
        }

        final ThreadPoolExecutor exec = new ThreadPoolExecutor(
                MAX_PARALLEL, MAX_PARALLEL + 2, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            final Thread t = new Thread(r, "spoongecko-download");
            t.setDaemon(true);
            return t;
        });
        exec.allowCoreThreadTimeOut(true);
        this.pool = exec;

        final NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress and results of file downloads");
        ch.setShowBadge(false);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    // ------------------------------------------------------------------
    // Listeners & Queries (UI thread)
    // ------------------------------------------------------------------

    void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            try { l.onDownloadsChanged(); } catch (RuntimeException ignored) {}
        }
    }

    List<DownloadEntry> getEntries() {
        final List<DownloadEntry> list = new ArrayList<>(entries.size());
        for (DownloadEntry e : entries.values()) list.add(e.copy());
        // Show newest first, keeping active/paused items easy to find at top if updated recently
        Collections.sort(list, (a, b) -> {
            final boolean aActive = a.status.isInProgressOrPaused();
            final boolean bActive = b.status.isInProgressOrPaused();
            if (aActive != bActive) return aActive ? -1 : 1;
            return Long.compare(b.createdAt, a.createdAt);
        });
        return list;
    }

    int getActiveCount() {
        int count = 0;
        for (DownloadEntry e : entries.values()) {
            if (e.status.isActive()) count++;
        }
        return count;
    }

    boolean hasActiveJobs() {
        return !activeJobs.isEmpty();
    }

    // ------------------------------------------------------------------
    // Entry points (UI thread)
    // ------------------------------------------------------------------

    static DownloadRequest requestFromResponse(WebResponse r, Runnable onStreamDone) {
        final String encoding = header(r.headers, "content-encoding");
        long length = -1;
        if (encoding == null || encoding.trim().isEmpty() || encoding.trim().equalsIgnoreCase("identity")) {
            length = parseLong(header(r.headers, "content-length"));
        }
        return new DownloadRequest(r.uri, null, header(r.headers, "content-type"),
                header(r.headers, "content-disposition"), length, r.body, onStreamDone);
    }

    long enqueue(GeckoRuntime runtime, DownloadRequest request) {
        final long id = nextId++;
        final long now = System.currentTimeMillis();
        final String name = DownloadNames.fileName(
                request.url, request.dispositionHint, request.mimeHint, LOOKUP);
        final String mime = DownloadNames.effectiveMime(name, request.mimeHint, LOOKUP);
        final boolean resumable = request.stream == null && isHttpUrl(request.url);

        final DownloadEntry entry = new DownloadEntry(
                id, request.url, request.referrer, mime, request.dispositionHint,
                name, request.length, resumable, now);
        entries.put(id, entry);

        final ActiveJob job = new ActiveJob(entry, request, runtime);
        toast("Downloading " + entry.fileName);
        scheduleOrStart(job, request.stream != null);
        return id;
    }

    void pause(long id) {
        final DownloadEntry entry = entries.get(id);
        if (entry == null || !entry.canPause()) return;
        final ActiveJob job = activeJobs.get(id);
        if (job == null) return;
        if (waitingQueue.remove(job)) {
            activeJobs.remove(id);
            entry.status = DownloadEntry.Status.PAUSED;
            entry.speedBytesPerSec = 0;
            entry.errorMessage = null;
            entry.updatedAt = System.currentTimeMillis();
            leave(job);
            persistNow();
            notifyListeners();
            return;
        }
        job.paused.set(true);
        DownloadCore.closeQuietly(job.stream);
    }

    void resumeOrRetry(GeckoRuntime runtime, long id) {
        final DownloadEntry entry = entries.get(id);
        if (entry == null) return;
        if (activeJobs.containsKey(id)) return;
        if (!entry.canResume() && !entry.canRetry()) return;
        if (nm != null) nm.cancel(DONE_BASE + (int) (id & ID_MASK));

        // If failed/cancelled and not keeping partial (e.g. cancelled or non-resumable), ensure clean start
        if (entry.status == DownloadEntry.Status.CANCELLED) {
            store.deletePartFile(id);
            entry.bytesDone = 0;
        } else {
            final File part = store.partFileFor(id);
            entry.bytesDone = (entry.resumable && part.exists()) ? part.length() : 0L;
        }
        entry.status = DownloadEntry.Status.QUEUED;
        entry.errorMessage = null;
        entry.speedBytesPerSec = 0;
        entry.updatedAt = System.currentTimeMillis();

        final DownloadRequest req = DownloadRequest.forUrl(entry.url, entry.referrer);
        final ActiveJob job = new ActiveJob(entry, req, runtime != null ? runtime : cachedRuntime);
        scheduleOrStart(job, false);
    }

    void cancel(long id) {
        final DownloadEntry entry = entries.get(id);
        if (entry == null) return;
        final ActiveJob job = activeJobs.get(id);
        if (job != null) {
            if (waitingQueue.remove(job)) {
                finishCancelled(job);
                return;
            }
            job.cancelled.set(true);
            DownloadCore.closeQuietly(job.stream);
            if (job.stream == null) finishCancelled(job);
        } else if (entry.status == DownloadEntry.Status.PAUSED) {
            store.deletePartFile(id);
            entry.status = DownloadEntry.Status.CANCELLED;
            entry.speedBytesPerSec = 0;
            entry.errorMessage = null;
            entry.updatedAt = System.currentTimeMillis();
            if (nm != null) nm.cancel(PROGRESS_BASE + (int) (id & ID_MASK));
            persistNow();
            notifyListeners();
            toast("Download cancelled");
        }
    }

    void pauseAll() {
        for (Long id : new ArrayList<>(activeJobs.keySet())) {
            pause(id);
        }
    }

    void resumeAll(GeckoRuntime runtime) {
        for (DownloadEntry e : getEntries()) {
            if (e.canResume()) resumeOrRetry(runtime, e.id);
        }
    }

    void removeEntry(long id, boolean deleteFile) {
        final DownloadEntry entry = entries.get(id);
        if (entry == null) return;
        if (activeJobs.containsKey(id) || entry.status == DownloadEntry.Status.PAUSED) {
            cancel(id);
        }
        if (deleteFile) {
            deleteBackedFile(entry);
        }
        store.deletePartFile(id);
        entries.remove(id);
        if (nm != null) {
            nm.cancel(PROGRESS_BASE + (int) (id & ID_MASK));
            nm.cancel(DONE_BASE + (int) (id & ID_MASK));
        }
        persistNow();
        notifyListeners();
    }

    void clearFinished() {
        boolean changed = false;
        for (Long id : new ArrayList<>(entries.keySet())) {
            final DownloadEntry e = entries.get(id);
            if (e != null && !e.status.isInProgressOrPaused()) {
                store.deletePartFile(id);
                entries.remove(id);
                if (nm != null) nm.cancel(DONE_BASE + (int) (id & ID_MASK));
                changed = true;
            }
        }
        if (changed) {
            persistNow();
            notifyListeners();
        }
    }

    void clearStaleNotifications() {
        if (!activeJobs.isEmpty() || nm == null) return;
        try {
            for (StatusBarNotification n : nm.getActiveNotifications()) {
                final int id = n.getId();
                if (id >= PROGRESS_BASE && id <= PROGRESS_BASE + ID_MASK) nm.cancel(id);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "clearing stale download notifications failed", e);
        }
    }

    Notification placeholderNotification() {
        return new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloads")
                .setContentIntent(managerPendingIntent())
                .build();
    }

    // ------------------------------------------------------------------
    // Queue & Job execution
    // ------------------------------------------------------------------

    private void scheduleOrStart(ActiveJob job, boolean immediateStream) {
        activeJobs.put(job.entry.id, job);
        if (immediateStream || runningCount() < MAX_PARALLEL) {
            job.entry.status = DownloadEntry.Status.RUNNING;
            job.entry.updatedAt = System.currentTimeMillis();
            postProgress(job);
            pinIfNeeded(job);
            updateWakeLock();
            persistNow();
            notifyListeners();
            start(job);
        } else {
            job.entry.status = DownloadEntry.Status.QUEUED;
            job.entry.updatedAt = System.currentTimeMillis();
            waitingQueue.add(job);
            postProgress(job);
            updateWakeLock();
            persistNow();
            notifyListeners();
        }
    }

    private int runningCount() {
        int count = 0;
        for (ActiveJob j : activeJobs.values()) {
            if (j.entry.status == DownloadEntry.Status.RUNNING
                    || j.entry.status == DownloadEntry.Status.SAVING) {
                count++;
            }
        }
        return count;
    }

    private void pumpQueue() {
        while (runningCount() < MAX_PARALLEL && !waitingQueue.isEmpty()) {
            final ActiveJob next = waitingQueue.poll();
            if (next == null || next.cancelled.get() || next.paused.get()) continue;
            if (!activeJobs.containsKey(next.entry.id)) continue;
            next.entry.status = DownloadEntry.Status.RUNNING;
            next.entry.updatedAt = System.currentTimeMillis();
            next.lastSpeedTimeMs = System.currentTimeMillis();
            next.lastSpeedBytes = next.entry.bytesDone;
            postProgress(next);
            pinIfNeeded(next);
            updateWakeLock();
            persistNow();
            notifyListeners();
            start(next);
        }
    }

    private void start(final ActiveJob job) {
        final DownloadRequest r = job.request;
        final DownloadEntry e = job.entry;
        if (r.stream != null) {
            submitDirectStream(job, r.stream, r.length);
        } else if (DataUri.isDataUri(r.url)) {
            pool.execute(() -> {
                final DataUri.Parsed p = DataUri.parse(r.url);
                if (p == null) {
                    main.post(() -> fail(job, "The data in this address is not valid"));
                    return;
                }
                e.fileName = DownloadNames.fileName(r.url, null, p.mime, LOOKUP);
                e.mimeType = DownloadNames.effectiveMime(e.fileName, p.mime, LOOKUP);
                e.totalBytes = p.data.length;
                workDirect(job, new ByteArrayInputStream(p.data), p.data.length);
            });
        } else {
            fetch(job);
        }
    }

    private GeckoWebExecutor executorFor(GeckoRuntime runtime) {
        final GeckoRuntime rt = runtime != null ? runtime : ExtensionManager.ensureRuntime(app);
        if (cachedExecutor == null || cachedRuntime != rt) {
            cachedRuntime = rt;
            cachedExecutor = new GeckoWebExecutor(rt);
        }
        return cachedExecutor;
    }

    private void fetch(final ActiveJob job) {
        final DownloadEntry e = job.entry;
        final File partFile = store.partFileFor(e.id);
        final long existingBytes = (e.resumable && partFile.exists()) ? partFile.length() : 0L;
        e.bytesDone = existingBytes;

        try {
            final WebRequest.Builder b = new WebRequest.Builder(job.request.url);
            if (job.request.referrer != null && !job.request.referrer.isEmpty()) {
                b.referrer(job.request.referrer);
            }
            if (existingBytes > 0) {
                b.addHeader("Range", "bytes=" + existingBytes + "-");
                if (e.eTag != null && !e.eTag.isEmpty()) {
                    b.addHeader("If-Range", e.eTag);
                }
            }
            executorFor(job.runtime).fetch(b.build()).accept(
                    resp -> {
                        if (resp == null) { fail(job, "No response"); return; }
                        if (job.cancelled.get()) {
                            DownloadCore.closeQuietly(resp.body);
                            finishCancelled(job);
                            return;
                        }
                        if (job.paused.get()) {
                            DownloadCore.closeQuietly(resp.body);
                            finishPaused(job, existingBytes);
                            return;
                        }
                        if (resp.statusCode == 416 && existingBytes > 0 && !job.resetRangeRetried) {
                            DownloadCore.closeQuietly(resp.body);
                            job.resetRangeRetried = true;
                            store.deletePartFile(e.id);
                            e.bytesDone = 0L;
                            fetch(job);
                            return;
                        }
                        if (resp.statusCode >= 400) {
                            DownloadCore.closeQuietly(resp.body);
                            fail(job, "The server answered HTTP " + resp.statusCode);
                            return;
                        }
                        if (resp.body == null) {
                            fail(job, "The server sent no data");
                            return;
                        }

                        final boolean isPartial = resp.statusCode == 206 && existingBytes > 0;
                        long initialDone = 0L;
                        boolean append = false;
                        long totalLength = -1L;

                        final String encoding = header(resp.headers, "content-encoding");
                        final boolean identityEncoding = encoding == null || encoding.trim().isEmpty()
                                || encoding.trim().equalsIgnoreCase("identity");
                        final long contentLen = identityEncoding
                                ? parseLong(header(resp.headers, "content-length")) : -1L;

                        if (isPartial) {
                            final DownloadCore.ContentRange cr =
                                    DownloadCore.parseContentRange(header(resp.headers, "content-range"));
                            if (cr == null || cr.start == existingBytes) {
                                append = true;
                                initialDone = existingBytes;
                                if (cr != null && cr.total > 0) {
                                    totalLength = cr.total;
                                } else if (contentLen > 0) {
                                    totalLength = existingBytes + contentLen;
                                } else if (e.totalBytes > 0) {
                                    totalLength = e.totalBytes;
                                }
                            } else {
                                // Unexpected range start: restart cleanly
                                totalLength = cr.total > 0 ? cr.total : contentLen;
                            }
                        } else {
                            totalLength = contentLen;
                        }

                        final String mimeHint = header(resp.headers, "content-type");
                        final String disp = header(resp.headers, "content-disposition");
                        final String etag = header(resp.headers, "etag");
                        if (!append) {
                            e.fileName = DownloadNames.fileName(
                                    resp.uri != null ? resp.uri : job.request.url, disp, mimeHint, LOOKUP);
                            e.mimeType = DownloadNames.effectiveMime(e.fileName, mimeHint, LOOKUP);
                        }
                        if (etag != null && !etag.trim().isEmpty()) e.eTag = etag.trim();
                        e.bytesDone = initialDone;
                        if (totalLength > 0) e.totalBytes = totalLength;
                        job.stream = resp.body;
                        job.lastSpeedTimeMs = System.currentTimeMillis();
                        job.lastSpeedBytes = initialDone;
                        try { resp.setReadTimeoutMillis(120_000); } catch (RuntimeException ignored) {}
                        postProgress(job);
                        notifyListeners();
                        submitStagedPart(job, resp.body, partFile, append, initialDone, e.totalBytes);
                    },
                    error -> fail(job, describe(error)));
        } catch (RuntimeException ex) {
            fail(job, describe(ex));
        }
    }

    private void submitDirectStream(final ActiveJob job, final InputStream in, final long total) {
        pool.execute(() -> workDirect(job, in, total));
    }

    private void submitStagedPart(final ActiveJob job, final InputStream in, final File partFile,
                                  final boolean append, final long initialDone, final long total) {
        pool.execute(() -> workStaged(job, in, partFile, append, initialDone, total));
    }

    // ------------------------------------------------------------------
    // Transfers (worker thread)
    // ------------------------------------------------------------------

    private void workDirect(final ActiveJob job, final InputStream in, final long total) {
        final DownloadEntry e = job.entry;
        final AndroidSink sink;
        try {
            sink = createSink(e.fileName, e.mimeType);
        } catch (RuntimeException ex) {
            DownloadCore.closeQuietly(in);
            main.post(() -> fail(job, describe(ex)));
            postStreamDone(job);
            return;
        }
        try {
            final DownloadCore.Result r = DownloadCore.run(
                    in, sink, 0L, total, job.paused, job.cancelled, true,
                    new DownloadCore.Throttle(PROGRESS_INTERVAL_MS, System::currentTimeMillis),
                    (done, tot) -> main.post(() -> onProgress(job, done, tot)));
            final Uri uri = sink.viewUri(app);
            final String localPath = sink.localPath();
            main.post(() -> finishOk(job, r.finalName, r.bytes, uri, localPath));
        } catch (DownloadCore.CancelledException ex) {
            main.post(() -> finishCancelled(job));
        } catch (DownloadCore.PausedException ex) {
            main.post(() -> finishCancelled(job));
        } catch (IOException | RuntimeException ex) {
            if (job.cancelled.get()) main.post(() -> finishCancelled(job));
            else main.post(() -> fail(job, describe(ex)));
        } finally {
            postStreamDone(job);
        }
    }

    private void workStaged(final ActiveJob job, final InputStream in, final File partFile,
                            final boolean append, final long initialDone, final long total) {
        final DownloadEntry e = job.entry;
        final PartFileSink partSink = new PartFileSink(partFile, append);
        final DownloadCore.Result r;
        try {
            r = DownloadCore.run(
                    in, partSink, initialDone, total, job.paused, job.cancelled, false,
                    new DownloadCore.Throttle(PROGRESS_INTERVAL_MS, System::currentTimeMillis),
                    (done, tot) -> main.post(() -> onProgress(job, done, tot)));
        } catch (DownloadCore.PausedException ex) {
            main.post(() -> finishPaused(job, ex.bytesDone));
            return;
        } catch (DownloadCore.CancelledException ex) {
            main.post(() -> finishCancelled(job));
            return;
        } catch (IOException | RuntimeException ex) {
            if (job.cancelled.get()) {
                partSink.abort();
                main.post(() -> finishCancelled(job));
            } else if (job.paused.get()) {
                main.post(() -> finishPaused(job, partFile.exists() ? partFile.length() : initialDone));
            } else {
                main.post(() -> fail(job, describe(ex)));
            }
            return;
        }

        // Move completed partial file into final Downloads storage
        main.post(() -> {
            if (activeJobs.containsKey(e.id)) {
                e.status = DownloadEntry.Status.SAVING;
                e.speedBytesPerSec = 0;
                e.bytesDone = r.bytes;
                if (e.totalBytes <= 0) e.totalBytes = r.bytes;
                notifyListeners();
            }
        });

        try {
            final AndroidSink dest = createSink(e.fileName, e.mimeType);
            final DownloadCore.Result saved;
            try (InputStream partIn = new FileInputStream(partFile)) {
                saved = DownloadCore.run(partIn, dest, r.bytes, job.cancelled, null, null);
            }
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            final Uri uri = dest.viewUri(app);
            final String localPath = dest.localPath();
            main.post(() -> finishOk(job, saved.finalName, saved.bytes, uri, localPath));
        } catch (DownloadCore.CancelledException ex) {
            partSink.abort();
            main.post(() -> finishCancelled(job));
        } catch (IOException | RuntimeException ex) {
            main.post(() -> fail(job, describe(ex)));
        }
    }

    private void postStreamDone(ActiveJob job) {
        final Runnable done = job.request == null ? null : job.request.onStreamDone;
        if (done != null) main.post(done);
    }

    // ------------------------------------------------------------------
    // Outcomes (UI thread)
    // ------------------------------------------------------------------

    private void onProgress(ActiveJob job, long done, long total) {
        if (!activeJobs.containsKey(job.entry.id)) return;
        final DownloadEntry e = job.entry;
        final long now = System.currentTimeMillis();
        final long dt = now - job.lastSpeedTimeMs;
        if (dt >= 400) {
            final long deltaBytes = Math.max(0L, done - job.lastSpeedBytes);
            final long instantSpeed = (deltaBytes * 1000L) / dt;
            e.speedBytesPerSec = e.speedBytesPerSec == 0
                    ? instantSpeed
                    : (e.speedBytesPerSec * 2L + instantSpeed) / 3L;
            job.lastSpeedTimeMs = now;
            job.lastSpeedBytes = done;
        }
        e.bytesDone = done;
        if (total > 0) e.totalBytes = total;
        e.updatedAt = now;
        postProgress(job);
        if (now - job.lastPersistMs >= PERSIST_INTERVAL_MS) {
            job.lastPersistMs = now;
            persistAsync();
        }
        notifyListeners();
    }

    private void finishOk(ActiveJob job, String finalName, long finalBytes, Uri uri, String localPath) {
        final DownloadEntry e = job.entry;
        if (activeJobs.remove(e.id) == null) return;
        leave(job);

        final String shown = finalName == null || finalName.isEmpty() ? e.fileName : finalName;
        e.fileName = shown;
        e.bytesDone = finalBytes;
        e.totalBytes = finalBytes;
        e.speedBytesPerSec = 0;
        e.status = DownloadEntry.Status.COMPLETED;
        e.errorMessage = null;
        e.contentUri = uri != null ? uri.toString() : null;
        e.localPath = localPath;
        e.updatedAt = System.currentTimeMillis();
        persistNow();
        notifyListeners();

        if (nm != null) {
            nm.notify(DONE_BASE + (int) (e.id & ID_MASK), new Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(shown)
                    .setContentText("Download complete \u00b7 " + Formatter.formatShortFileSize(app, finalBytes))
                    .setAutoCancel(true)
                    .setContentIntent(openPending(e, uri))
                    .build());
        }
        toast("Downloaded " + shown);
        pumpQueue();
    }

    private void finishPaused(ActiveJob job, long bytesDone) {
        final DownloadEntry e = job.entry;
        if (activeJobs.remove(e.id) == null) return;
        leave(job);
        e.bytesDone = Math.max(0L, bytesDone);
        e.speedBytesPerSec = 0;
        e.status = DownloadEntry.Status.PAUSED;
        e.errorMessage = null;
        e.updatedAt = System.currentTimeMillis();
        persistNow();
        notifyListeners();
        pumpQueue();
    }

    private void fail(ActiveJob job, String reason) {
        final DownloadEntry e = job.entry;
        if (activeJobs.remove(e.id) == null) return;
        leave(job);

        e.speedBytesPerSec = 0;
        e.status = DownloadEntry.Status.FAILED;
        e.errorMessage = reason;
        e.updatedAt = System.currentTimeMillis();
        persistNow();
        notifyListeners();

        if (nm != null) {
            final Notification.Builder b = new Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Download failed")
                    .setContentText(e.fileName + " \u2013 " + reason)
                    .setContentIntent(managerPendingIntent())
                    .setAutoCancel(true);
            if (e.canRetry()) {
                b.addAction(action(android.R.drawable.ic_popup_sync, "Retry", ACTION_RETRY, e.id));
            }
            nm.notify(DONE_BASE + (int) (e.id & ID_MASK), b.build());
        }
        toast("Download failed: " + reason);
        pumpQueue();
    }

    private void finishCancelled(ActiveJob job) {
        final DownloadEntry e = job.entry;
        if (activeJobs.remove(e.id) == null) return;
        leave(job);

        store.deletePartFile(e.id);
        e.speedBytesPerSec = 0;
        e.status = DownloadEntry.Status.CANCELLED;
        e.errorMessage = null;
        e.updatedAt = System.currentTimeMillis();
        persistNow();
        notifyListeners();
        toast("Download cancelled");
        pumpQueue();
    }

    private void leave(ActiveJob job) {
        if (job.pinned) {
            job.pinned = false;
            final ActiveJob next = firstRunningJob();
            if (next != null) pin(next);
            else DownloadService.release();
        }
        if (nm != null) nm.cancel(PROGRESS_BASE + (int) (job.entry.id & ID_MASK));
        updateWakeLock();
    }

    private void persistNow() {
        store.saveAsync(nextId, new ArrayList<>(entries.values()));
    }

    private void persistAsync() {
        store.saveAsync(nextId, new ArrayList<>(entries.values()));
    }

    // ------------------------------------------------------------------
    // Foreground service and wake lock
    // ------------------------------------------------------------------

    private void pinIfNeeded(ActiveJob job) {
        for (ActiveJob other : activeJobs.values()) if (other.pinned) return;
        pin(job);
    }

    private void pin(ActiveJob job) {
        job.pinned = true;
        DownloadService.pin(app, PROGRESS_BASE + (int) (job.entry.id & ID_MASK), buildProgress(job.entry));
    }

    private ActiveJob firstRunningJob() {
        for (ActiveJob j : activeJobs.values()) {
            if (j.entry.status == DownloadEntry.Status.RUNNING
                    || j.entry.status == DownloadEntry.Status.SAVING) {
                return j;
            }
        }
        return null;
    }

    private void updateWakeLock() {
        try {
            if (activeJobs.isEmpty()) {
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

    private void postProgress(ActiveJob job) {
        if (nm != null) {
            nm.notify(PROGRESS_BASE + (int) (job.entry.id & ID_MASK), buildProgress(job.entry));
        }
    }

    private Notification buildProgress(DownloadEntry e) {
        final boolean known = e.totalBytes > 0;
        final int percent = e.progressPercent();
        final String text;
        if (e.status == DownloadEntry.Status.QUEUED) {
            text = "Queued";
        } else if (e.status == DownloadEntry.Status.SAVING) {
            text = "Saving to Downloads\u2026";
        } else if (known) {
            final String speed = e.speedBytesPerSec > 0
                    ? " \u00b7 " + Formatter.formatShortFileSize(app, e.speedBytesPerSec) + "/s"
                    : "";
            text = percent + "% \u00b7 " + Formatter.formatShortFileSize(app, e.bytesDone)
                    + " of " + Formatter.formatShortFileSize(app, e.totalBytes) + speed;
        } else {
            final String speed = e.speedBytesPerSec > 0
                    ? " \u00b7 " + Formatter.formatShortFileSize(app, e.speedBytesPerSec) + "/s"
                    : "";
            text = e.bytesDone > 0
                    ? Formatter.formatShortFileSize(app, e.bytesDone) + speed
                    : "Starting\u2026";
        }

        final Notification.Builder b = new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(e.fileName)
                .setContentText(text)
                .setContentIntent(managerPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(known ? 100 : 0, percent, !known || e.status == DownloadEntry.Status.QUEUED);
        if (e.canPause()) {
            b.addAction(action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE, e.id));
        }
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", ACTION_CANCEL, e.id));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private Notification.Action action(int iconRes, String title, String action, long id) {
        final Intent i = new Intent(app, DownloadService.class).setAction(action).putExtra(EXTRA_ID, id);
        final int op;
        if (ACTION_CANCEL.equals(action)) op = 1;
        else if (ACTION_RETRY.equals(action)) op = 2;
        else op = 3;
        final PendingIntent pi = PendingIntent.getService(app, (int) ((id & 0xFFFF) * 4 + op), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(app, iconRes), title, pi).build();
    }

    private PendingIntent managerPendingIntent() {
        final Intent i = new Intent(app, DownloadsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(app, 0x67FF, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openPending(DownloadEntry e, Uri uri) {
        final Intent open;
        if (uri != null) {
            open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            open = new Intent(app, DownloadsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        return PendingIntent.getActivity(app, DONE_BASE + (int) (e.id & ID_MASK), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------------
    // Open / Share / Delete / Folder helpers
    // ------------------------------------------------------------------

    Uri resolveViewUri(DownloadEntry e) {
        if (e == null) return null;
        if (e.contentUri != null && !e.contentUri.isEmpty()) {
            return Uri.parse(e.contentUri);
        }
        if (e.localPath != null && !e.localPath.isEmpty()) {
            try {
                final File f = new File(e.localPath);
                if (f.exists()) return UploadFileProvider.uriForDownloadFile(app, f);
            } catch (IOException | RuntimeException ignored) {}
        }
        return null;
    }

    boolean isBackedFilePresent(DownloadEntry e) {
        final Uri uri = resolveViewUri(e);
        if (uri == null) return false;
        try (ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null;
        } catch (Exception ex) {
            return false;
        }
    }

    boolean openDownloadedFile(Context ctx, DownloadEntry e) {
        final Uri uri = resolveViewUri(e);
        if (uri == null || !isBackedFilePresent(e)) {
            Toast.makeText(ctx, "File was moved or deleted from device", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            final Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, e.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(view);
            return true;
        } catch (Exception ex) {
            try {
                final Intent generic = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(generic, "Open with"));
                return true;
            } catch (Exception ex2) {
                Toast.makeText(ctx, "No app available to open this file", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    boolean shareDownloadedFile(Context ctx, DownloadEntry e) {
        final Uri uri = resolveViewUri(e);
        if (uri == null || !isBackedFilePresent(e)) {
            Toast.makeText(ctx, "File was moved or deleted from device", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            final Intent share = new Intent(Intent.ACTION_SEND)
                    .setType(e.mimeType != null ? e.mimeType : "*/*")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(share, "Share " + e.fileName));
            return true;
        } catch (Exception ex) {
            Toast.makeText(ctx, "No share app available", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void deleteBackedFile(DownloadEntry e) {
        if (e.contentUri != null && !e.contentUri.isEmpty()) {
            try {
                final Uri uri = Uri.parse(e.contentUri);
                if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
                    app.getContentResolver().delete(uri, null, null);
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "could not delete MediaStore file", ex);
            }
        }
        if (e.localPath != null && !e.localPath.isEmpty()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(e.localPath).delete();
            } catch (RuntimeException ignored) {}
        }
    }

    static void openDownloadsFolder(Context ctx) {
        final String docId;
        if (Build.VERSION.SDK_INT >= 29) {
            docId = "primary:" + Environment.DIRECTORY_DOWNLOADS;
        } else {
            final File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            docId = dir == null
                    ? "primary:" + Environment.DIRECTORY_DOWNLOADS
                    : "primary:Android/data/" + ctx.getPackageName()
                    + "/files/" + Environment.DIRECTORY_DOWNLOADS;
        }
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", docId),
                    DocumentsContract.Document.MIME_TYPE_DIR));
            return;
        } catch (Exception ignored) {}
        try {
            ctx.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            Toast.makeText(ctx, "No app can open the Downloads folder", Toast.LENGTH_SHORT).show();
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "toast failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Storage Sinks
    // ------------------------------------------------------------------

    private AndroidSink createSink(String name, String mime) {
        if (Build.VERSION.SDK_INT >= 29) return new MediaStoreSink(app.getContentResolver(), name, mime);
        return new LegacySink(UploadFileProvider.downloadsRoot(app), name);
    }

    private static final class PartFileSink implements DownloadCore.Sink {
        private final File partFile;
        private final boolean append;

        PartFileSink(File partFile, boolean append) {
            this.partFile = partFile;
            this.append = append;
        }

        @Override public OutputStream open() throws IOException {
            final File parent = partFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create partial downloads folder");
            }
            return new FileOutputStream(partFile, append);
        }

        @Override public String commit() {
            return partFile.getName();
        }

        @Override public void abort() {
            if (partFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
            }
        }
    }

    private abstract static class AndroidSink implements DownloadCore.Sink {
        abstract Uri viewUri(Context ctx);
        String localPath() { return null; }
    }

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
            v.put(MediaStore.Downloads.IS_PENDING, 1);
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
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (RuntimeException ignored) {}
            return name;
        }

        @Override public void abort() {
            if (uri == null) return;
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) {}
        }

        @Override Uri viewUri(Context ctx) {
            return uri;
        }
    }

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

        @Override Uri viewUri(Context ctx) {
            if (file == null) return null;
            try {
                return UploadFileProvider.uriForDownloadFile(ctx, file);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override String localPath() {
            return file == null ? null : file.getAbsolutePath();
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

    private static boolean isHttpUrl(String url) {
        if (url == null) return false;
        final String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

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
