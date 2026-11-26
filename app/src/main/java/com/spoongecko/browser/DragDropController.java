package com.spoongecko.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Handles OS-level split-screen / multi-window file drag-and-drop onto GeckoView:
 *  - Requests DragAndDropPermissions during ACTION_DROP and acquires streams immediately
 *    while transient read grants are active;
 *  - Stages dropped files into cache/uploads/ via FilePicker's stream/fallback pipeline;
 *  - Completes an active FilePrompt if one is open, or forwards reconstructed File objects
 *    into the web page (DataTransfer drop events + nearby <input type=file> fallback) via the
 *    built-in WebExtension native messaging port ("spoongeckoDrop").
 */
final class DragDropController {

    private static final String TAG = "SpoonGecko";
    private static final int CHUNK_BYTES = 384 * 1024;
    private static final long MAX_IPC_DROP_BYTES = 48L * 1024 * 1024;
    private static final long DROP_RESULT_TIMEOUT_MS = 2500L;

    private static final class PendingDropOutcome {
        final String label;
        final Runnable timeoutTask;

        PendingDropOutcome(String label, Runnable timeoutTask) {
            this.label = label;
            this.timeoutTask = timeoutTask;
        }
    }

    private static final class StagedDropFile {
        final File file;
        final String mime;

        StagedDropFile(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }

    private final Activity activity;
    private final GeckoView geckoView;
    private final FrameLayout contentContainer;
    private final ScreenController screen;
    private final TabManager tabManager;
    private final FilePicker filePicker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;

    private final Set<View> activeDragViews = new HashSet<>();
    private final Map<GeckoSession, WebExtension.Port> portsBySession =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, PendingDropOutcome> pendingOutcomes = new HashMap<>();

    private TextView dropBanner;
    private DragEvent lastHandledDropEvent;
    private boolean destroyed;

    private final View.OnDragListener dragListener = this::onDragEvent;

    DragDropController(Activity activity,
                       GeckoView geckoView,
                       FrameLayout contentContainer,
                       ScreenController screen,
                       TabManager tabManager,
                       FilePicker filePicker) {
        this.activity = activity;
        this.geckoView = geckoView;
        this.contentContainer = contentContainer;
        this.screen = screen;
        this.tabManager = tabManager;
        this.filePicker = filePicker;

        final ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            final Thread t = new Thread(r, "spoongecko-dragdrop");
            t.setDaemon(true);
            return t;
        });
        exec.allowCoreThreadTimeOut(true);
        this.worker = exec;

        installBanner();
        if (geckoView != null) geckoView.setOnDragListener(dragListener);
        if (contentContainer != null) contentContainer.setOnDragListener(dragListener);
    }

    void attachSession(final GeckoSession session) {
        if (destroyed || session == null) return;
        final WeakReference<GeckoSession> sessionRef = new WeakReference<>(session);
        final WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                final GeckoSession s = sessionRef.get();
                if (destroyed || port == null || s == null) return;
                portsBySession.put(s, port);
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override
                    public void onPortMessage(Object message, WebExtension.Port p) {
                        handlePortMessage(message);
                    }

                    @Override
                    public void onDisconnect(WebExtension.Port p) {
                        final GeckoSession curSession = sessionRef.get();
                        if (curSession != null && portsBySession.get(curSession) == p) {
                            portsBySession.remove(curSession);
                        }
                    }
                });
            }
        };
        ExtensionManager.get().attachDropHelper(session, delegate);
    }

    void destroy() {
        destroyed = true;
        if (geckoView != null) geckoView.setOnDragListener(null);
        if (contentContainer != null) contentContainer.setOnDragListener(null);
        activeDragViews.clear();
        for (PendingDropOutcome out : new ArrayList<>(pendingOutcomes.values())) {
            main.removeCallbacks(out.timeoutTask);
        }
        pendingOutcomes.clear();
        for (GeckoSession s : new ArrayList<>(portsBySession.keySet())) {
            ExtensionManager.get().detachDropHelper(s);
        }
        portsBySession.clear();
        worker.shutdownNow();
    }

    private void installBanner() {
        if (contentContainer == null) return;
        final TextView banner = new TextView(activity);
        banner.setText("Drop file to attach");
        banner.setTextColor(0xFFFFFFFF);
        banner.setTextSize(14f);
        banner.setTypeface(null, Typeface.BOLD);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundResource(R.drawable.bg_glass_dialog);
        banner.setPadding(dp(20), dp(10), dp(20), dp(10));
        banner.setElevation(dp(8));
        banner.setClickable(false);
        banner.setFocusable(false);
        banner.setVisibility(View.GONE);

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(18);
        contentContainer.addView(banner, lp);
        this.dropBanner = banner;
    }

    private void showDropBanner(boolean visible) {
        if (dropBanner == null || destroyed) return;
        final int target = visible ? View.VISIBLE : View.GONE;
        if (dropBanner.getVisibility() != target) {
            dropBanner.setVisibility(target);
        }
    }

    private boolean onDragEvent(View v, DragEvent event) {
        if (destroyed || event == null) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: {
                if (event.getLocalState() != null) return false;
                if (screen != null && screen.isHomePanelVisible()
                        && (filePicker == null || !filePicker.hasPendingPrompt())) {
                    return false;
                }
                final ClipDescription desc = event.getClipDescription();
                if (desc != null && isPlainTextOnlyDrag(desc)) {
                    return false;
                }
                return true;
            }
            case DragEvent.ACTION_DRAG_ENTERED:
                activeDragViews.add(v);
                showDropBanner(true);
                return true;

            case DragEvent.ACTION_DRAG_LOCATION:
                return true;

            case DragEvent.ACTION_DRAG_EXITED:
                activeDragViews.remove(v);
                if (activeDragViews.isEmpty()) showDropBanner(false);
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
                activeDragViews.clear();
                showDropBanner(false);
                return true;

            case DragEvent.ACTION_DROP:
                activeDragViews.clear();
                showDropBanner(false);
                if (event == lastHandledDropEvent) return true;
                lastHandledDropEvent = event;
                return handleDrop(v, event);

            default:
                return false;
        }
    }

    private static boolean isPlainTextOnlyDrag(ClipDescription desc) {
        final int count = desc.getMimeTypeCount();
        if (count != 1) return false;
        final String mime = desc.getMimeType(0);
        return ClipDescription.MIMETYPE_TEXT_PLAIN.equalsIgnoreCase(mime)
                || ClipDescription.MIMETYPE_TEXT_HTML.equalsIgnoreCase(mime);
    }

    private boolean handleDrop(View targetView, DragEvent event) {
        final ClipData clip = event.getClipData();
        if (clip == null || clip.getItemCount() == 0) return false;

        final List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            final ClipData.Item item = clip.getItemAt(i);
            if (item == null) continue;
            Uri u = item.getUri();
            if (u == null && item.getIntent() != null) {
                u = item.getIntent().getData();
            }
            if (u != null && !uris.contains(u)) {
                uris.add(u);
            }
        }
        if (uris.isEmpty()) return false;

        float gx = event.getX();
        float gy = event.getY();
        if (geckoView != null && targetView != geckoView) {
            gx -= geckoView.getLeft() - targetView.getLeft();
            gy -= geckoView.getTop() - targetView.getTop();
        }
        final int gw = geckoView != null ? Math.max(1, geckoView.getWidth()) : 1;
        final int gh = geckoView != null ? Math.max(1, geckoView.getHeight()) : 1;
        final float xRatio = Math.max(0f, Math.min(1f, gx / gw));
        final float yRatio = Math.max(0f, Math.min(1f, gy / gh));

        DragAndDropPermissions dropPerms = null;
        try {
            dropPerms = activity.requestDragAndDropPermissions(event);
        } catch (RuntimeException e) {
            Log.w(TAG, "requestDragAndDropPermissions failed", e);
        }
        final DragAndDropPermissions finalPerms = dropPerms;

        final Context appContext = activity.getApplicationContext();
        final ContentResolver activityCr = activity.getContentResolver();
        final ContentResolver appCr = appContext.getContentResolver();
        final List<FilePicker.OpenedSource> openedSources = new ArrayList<>(uris.size());
        boolean big = false;

        for (Uri u : uris) {
            Log.i(TAG, "drag drop URI scheme=" + u.getScheme() + " authority=" + u.getAuthority());
            if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(u.getScheme())) {
                final String path = u.getPath();
                final File f = path != null ? new File(path) : null;
                if (f != null && f.isFile() && f.length() >= FilePicker.BIG_FILE_BYTES) big = true;
                openedSources.add(new FilePicker.OpenedSource(
                        u, null, null, null, guessMimeFromUri(u), null, null, null));
            } else {
                FilePicker.UriMeta meta = FilePicker.queryMeta(activityCr, u);
                if (meta.name == null && meta.size < 0 && activityCr != appCr) {
                    meta = FilePicker.queryMeta(appCr, u);
                }
                if (meta.size >= FilePicker.BIG_FILE_BYTES) big = true;

                String mime = null;
                try {
                    mime = activityCr.getType(u);
                } catch (RuntimeException ignored) {
                }
                if (mime == null && activityCr != appCr) {
                    try {
                        mime = appCr.getType(u);
                    } catch (RuntimeException ignored) {
                    }
                }
                if (mime == null) {
                    mime = guessMimeFromName(meta.name);
                }

                final FilePicker.StreamError streamErr = new FilePicker.StreamError();
                FilePicker.ResolvedStream stream =
                        FilePicker.openBestStreamInternal(activityCr, u, streamErr);
                if (stream == null && activityCr != appCr) {
                    stream = FilePicker.openBestStreamInternal(appCr, u, streamErr);
                }
                openedSources.add(new FilePicker.OpenedSource(
                        u, null, stream, meta.name, mime,
                        streamErr.stage, streamErr.summary, streamErr.cause));
            }
        }

        if (big) toast("Preparing dropped file\u2026");

        final Tab activeTab = tabManager != null ? tabManager.getActiveTab() : null;
        final GeckoSession targetSession = activeTab != null ? activeTab.session : null;

        worker.execute(() -> {
            final List<Uri> localUris = new ArrayList<>(openedSources.size());
            final List<String> localMimes = new ArrayList<>(openedSources.size());
            int failed = 0;
            FilePicker.AttachmentDiagnostic lastDiag = null;

            try {
                for (FilePicker.OpenedSource src : openedSources) {
                    if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(src.source.getScheme())) {
                        final FilePicker.StreamError localErr = new FilePicker.StreamError();
                        final Uri copy = FilePicker.copyLocalFileToCache(
                                appContext, activityCr, appCr, src.source, localErr);
                        if (copy != null) {
                            localUris.add(copy);
                            localMimes.add(src.mime != null ? src.mime : guessMimeFromUri(copy));
                        } else {
                            failed++;
                            lastDiag = new FilePicker.AttachmentDiagnostic(
                                    localErr.stage != null ? localErr.stage : "copyLocalFile",
                                    FilePicker.PickerMode.DRAG_DROP, src.source, 0,
                                    localErr.summary != null ? localErr.summary : "unreadable local file",
                                    localErr.cause);
                        }
                    } else if (src.stream == null) {
                        failed++;
                        lastDiag = new FilePicker.AttachmentDiagnostic(
                                src.openStage != null ? src.openStage : "openBestStream",
                                FilePicker.PickerMode.DRAG_DROP, src.source, 0,
                                src.openError != null ? src.openError : "could not open dropped file",
                                src.openCause);
                    } else {
                        final FilePicker.StreamError writeErr = new FilePicker.StreamError();
                        final Uri copy = FilePicker.writeStreamToCache(
                                appContext, src.stream, src.name, src.mime, writeErr);
                        if (copy != null) {
                            localUris.add(copy);
                            final String mime = src.mime != null ? src.mime : guessMimeFromUri(copy);
                            localMimes.add(mime != null ? mime : "application/octet-stream");
                        } else {
                            failed++;
                            lastDiag = new FilePicker.AttachmentDiagnostic(
                                    writeErr.stage != null ? writeErr.stage : "writeStreamToCache",
                                    FilePicker.PickerMode.DRAG_DROP, src.source, 0,
                                    writeErr.summary != null ? writeErr.summary : "could not copy dropped file",
                                    writeErr.cause);
                        }
                    }
                }
            } finally {
                if (finalPerms != null) {
                    main.post(() -> {
                        try {
                            finalPerms.release();
                        } catch (RuntimeException ignored) {
                        }
                    });
                }
            }

            final int failedCount = failed;
            final FilePicker.AttachmentDiagnostic diag = lastDiag;

            // Check if a <input type=file> prompt is currently open and waiting
            if (filePicker != null && filePicker.hasPendingPrompt()) {
                main.post(() -> {
                    if (destroyed) return;
                    if (!filePicker.deliverExternalFiles(localUris, failedCount, diag)) {
                        dispatchStagedFilesToPage(targetSession, xRatio, yRatio,
                                localUris, localMimes, failedCount, diag);
                    }
                });
                return;
            }

            if (localUris.isEmpty()) {
                main.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed()) return;
                    if (filePicker != null && diag != null) {
                        filePicker.showStandaloneDropError(diag);
                    } else {
                        toast("Couldn't read the dropped file");
                    }
                });
                return;
            }

            dispatchStagedFilesToPage(targetSession, xRatio, yRatio,
                    localUris, localMimes, failedCount, diag);
        });

        return true;
    }

    private void dispatchStagedFilesToPage(GeckoSession session,
                                           float xRatio,
                                           float yRatio,
                                           List<Uri> localUris,
                                           List<String> localMimes,
                                           int failedCount,
                                           FilePicker.AttachmentDiagnostic diag) {
        if (destroyed || localUris.isEmpty()) return;
        final WebExtension.Port port = session != null ? portsBySession.get(session) : null;
        if (port == null) {
            main.post(() -> {
                if (destroyed) return;
                if (failedCount > 0 && diag != null && filePicker != null) {
                    filePicker.showStandaloneDropError(diag);
                } else {
                    toast("Drop isn't supported on this page \u2014 use the site's upload button");
                }
            });
            return;
        }

        final List<StagedDropFile> staged = new ArrayList<>(localUris.size());
        long totalBytes = 0L;
        for (int i = 0; i < localUris.size(); i++) {
            final Uri u = localUris.get(i);
            final String path = u.getPath();
            if (path == null) continue;
            final File f = new File(path);
            if (!f.isFile() || !f.canRead()) continue;
            totalBytes += f.length();
            final String mime = i < localMimes.size() && localMimes.get(i) != null
                    ? localMimes.get(i) : "application/octet-stream";
            staged.add(new StagedDropFile(f, mime));
        }

        if (staged.isEmpty()) {
            main.post(() -> toast("Couldn't prepare the dropped file"));
            return;
        }
        if (totalBytes > MAX_IPC_DROP_BYTES) {
            main.post(() -> toast("File too large for drag & drop \u2014 tap the upload button instead"));
            return;
        }

        final String dropId = UUID.randomUUID().toString();
        final String label = staged.size() == 1
                ? staged.get(0).file.getName()
                : staged.size() + " files";

        main.post(() -> {
            if (destroyed) return;
            final Runnable timeout = () -> {
                final PendingDropOutcome expired = pendingOutcomes.remove(dropId);
                if (expired != null && !destroyed) {
                    toast("Page didn't accept the dropped file \u2014 try tapping the upload button");
                }
            };
            pendingOutcomes.put(dropId, new PendingDropOutcome(label, timeout));
            main.postDelayed(timeout, DROP_RESULT_TIMEOUT_MS);
        });

        try {
            final JSONArray filesJson = new JSONArray();
            final byte[] buf = new byte[CHUNK_BYTES];

            for (int i = 0; i < staged.size(); i++) {
                final StagedDropFile item = staged.get(i);
                final JSONObject meta = new JSONObject();
                meta.put("name", item.file.getName());
                meta.put("mime", item.mime);
                filesJson.put(meta);

                try (InputStream in = new FileInputStream(item.file)) {
                    int read;
                    while ((read = in.read(buf)) > 0) {
                        final String b64 = Base64.encodeToString(buf, 0, read, Base64.NO_WRAP);
                        final JSONObject chunkMsg = new JSONObject();
                        chunkMsg.put("type", "chunk");
                        chunkMsg.put("dropId", dropId);
                        chunkMsg.put("fileIndex", i);
                        chunkMsg.put("data", b64);
                        postOnMain(port, chunkMsg);
                    }
                }
            }

            final JSONObject commitMsg = new JSONObject();
            commitMsg.put("type", "commit");
            commitMsg.put("dropId", dropId);
            commitMsg.put("xRatio", xRatio);
            commitMsg.put("yRatio", yRatio);
            commitMsg.put("files", filesJson);
            postOnMain(port, commitMsg);

            if (failedCount > 0) {
                main.post(() -> toast(failedCount == 1
                        ? "1 dropped file couldn't be read"
                        : failedCount + " dropped files couldn't be read"));
            }
        } catch (Exception e) {
            Log.w(TAG, "failed sending dropped files to page", e);
            main.post(() -> {
                final PendingDropOutcome out = pendingOutcomes.remove(dropId);
                if (out != null) main.removeCallbacks(out.timeoutTask);
                toast("Couldn't forward dropped file to page");
            });
        }
    }

    private void postOnMain(WebExtension.Port port, JSONObject msg) {
        main.post(() -> {
            if (destroyed) return;
            try {
                port.postMessage(msg);
            } catch (RuntimeException e) {
                Log.w(TAG, "port.postMessage failed", e);
            }
        });
    }

    private void handlePortMessage(Object raw) {
        if (!(raw instanceof JSONObject)) return;
        final JSONObject obj = (JSONObject) raw;
        if (!"dropResult".equals(obj.optString("type"))) return;
        final String dropId = obj.optString("dropId", "");
        final boolean handled = obj.optBoolean("handled", false);
        main.post(() -> {
            final PendingDropOutcome outcome = pendingOutcomes.remove(dropId);
            if (outcome == null) return;
            main.removeCallbacks(outcome.timeoutTask);
            if (destroyed) return;
            if (handled) {
                toast("Attached " + outcome.label);
            } else {
                toast("No drop zone or file input found there");
            }
        });
    }

    private static String guessMimeFromUri(Uri u) {
        if (u == null) return null;
        return guessMimeFromName(u.getLastPathSegment());
    }

    private static String guessMimeFromName(String name) {
        if (name == null) return null;
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return null;
        final String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    private void toast(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!destroyed) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        } else {
            main.post(() -> {
                if (!destroyed) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
