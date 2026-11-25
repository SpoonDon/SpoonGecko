package com.spoongecko.browser;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [UPLOAD] Attaching local or captured files to a web page ({@code <input type=file>}).
 *
 * Fixes:
 *  - Acquires content:// input streams synchronously inside {@link #handleActivityResult} before
 *    transient URI read grants can expire, then copies bytes off the UI thread;
 *  - Uses a 3-tier stream resolver (openInputStream -> openTypedAssetFileDescriptor for virtual
 *    or cloud documents -> openFileDescriptor);
 *  - Supports zero-permission Android Photo Picker on API 33+ for image/video inputs, plus HTML
 *    {@code capture} (camera, camcorder, audio) via {@link UploadFileProvider};
 *  - Queues confirmation until the Activity and GeckoSession have resumed, using the matching
 *    single-URI or multi-URI {@link FilePrompt#confirm} overload.
 */
final class FilePicker {

    private static final String TAG = "SpoonGecko";
    private static final String PICKER_GATE_PREF = "browser.disable_pickers_background_tabs";
    private static volatile boolean pickerGateRelaxed;

    private static final int REQ_BASE = 0x7110;
    private static final int SLOTS = 16;
    private static final int REQ_CAPTURE_PERM = 0x7120;

    private static final String UPLOAD_DIR = "uploads";
    private static final long KEEP_COPIES_MS = 24L * 60 * 60 * 1000;
    private static final long BIG_FILE_BYTES = 8L * 1024 * 1024;

    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-upload");
        t.setDaemon(true);
        return t;
    });

    // UI thread only.
    private boolean activityResumed = true;
    private FilePrompt pendingPrompt;
    private GeckoResult<PromptResponse> pendingResult;
    private int pendingSlot;
    private int nextSlot;
    private File pendingCaptureFile;
    private PendingCaptureRequest pendingPermissionCapture;
    private PendingDelivery pendingDelivery;

    private static final class PendingCaptureRequest {
        final FilePrompt prompt;
        final GeckoResult<PromptResponse> result;
        final String[] types;
        final boolean multiple;
        final int captureCategory;

        PendingCaptureRequest(FilePrompt prompt, GeckoResult<PromptResponse> result,
                              String[] types, boolean multiple, int captureCategory) {
            this.prompt = prompt;
            this.result = result;
            this.types = types;
            this.multiple = multiple;
            this.captureCategory = captureCategory;
        }
    }

    private static final class PendingDelivery {
        final FilePrompt prompt;
        final GeckoResult<PromptResponse> result;
        final List<Uri> copies;
        final int failed;
        final String lastError;

        PendingDelivery(FilePrompt prompt, GeckoResult<PromptResponse> result,
                        List<Uri> copies, int failed, String lastError) {
            this.prompt = prompt;
            this.result = result;
            this.copies = copies;
            this.failed = failed;
            this.lastError = lastError;
        }
    }

    private static final class OpenedSource {
        final Uri source;
        final ResolvedStream stream;
        final String name;
        final String mime;
        final String openError;

        OpenedSource(Uri source, ResolvedStream stream, String name, String mime, String openError) {
            this.source = source;
            this.stream = stream;
            this.name = name;
            this.mime = mime;
            this.openError = openError;
        }
    }

    FilePicker(Activity activity) {
        this.activity = activity;
        worker.execute(this::deleteOldCopies);
        relaxPickerGate();
    }

    void onResume() {
        activityResumed = true;
        final PendingDelivery delivery = pendingDelivery;
        if (delivery != null) {
            pendingDelivery = null;
            deliver(delivery.prompt, delivery.result, delivery.copies,
                    delivery.failed, delivery.lastError);
        }
    }

    void onPause() {
        activityResumed = false;
    }

    private static void relaxPickerGate() {
        if (pickerGateRelaxed) return;
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(FilePicker::setPickerGatePref);
        main.postDelayed(FilePicker::setPickerGatePref, 3000);
    }

    private static void setPickerGatePref() {
        try {
            GeckoPreferenceController.setGeckoPref(
                    PICKER_GATE_PREF, Boolean.FALSE, GeckoPreferenceController.PREF_BRANCH_USER);
            pickerGateRelaxed = true;
        } catch (Throwable t) {
            Log.w(TAG, "could not switch off the engine's file-picker rule", t);
        }
    }

    // ------------------------------------------------------------------
    // The page asks for a file
    // ------------------------------------------------------------------

    GeckoResult<PromptResponse> onFilePrompt(final FilePrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        Log.i(TAG, "file prompt: type=" + prompt.type
                + " capture=" + prompt.capture
                + " accept=" + Arrays.toString(prompt.mimeTypes));
        if (activity.isFinishing() || activity.isDestroyed()) {
            result.complete(prompt.dismiss());
            return result;
        }
        if (prompt.type == FilePrompt.Type.FOLDER) {
            toast("Uploading a whole folder isn't supported");
            result.complete(prompt.dismiss());
            return result;
        }

        dismissPending();
        worker.execute(this::deleteOldCopies);

        final boolean multiple = prompt.type == FilePrompt.Type.MULTIPLE;
        final String[] types = UploadFiles.acceptedTypes(prompt.mimeTypes, platformResolver());

        if (prompt.capture != FilePrompt.Capture.NONE) {
            final int category = UploadFiles.captureCategory(types);
            final String neededPerm = permissionForCapture(category);
            if (neededPerm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && activity.checkSelfPermission(neededPerm) != PackageManager.PERMISSION_GRANTED) {
                pendingPermissionCapture = new PendingCaptureRequest(
                        prompt, result, types, multiple, category);
                activity.requestPermissions(new String[]{neededPerm}, REQ_CAPTURE_PERM);
                return result;
            }
            if (launchCapture(prompt, result, category)) {
                return result;
            }
        }

        launchDocumentOrMediaPicker(prompt, result, types, multiple);
        return result;
    }

    boolean handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_CAPTURE_PERM) return false;
        final PendingCaptureRequest req = pendingPermissionCapture;
        pendingPermissionCapture = null;
        if (req == null) return true;
        if (activity.isFinishing() || activity.isDestroyed()) {
            completeDismissed(req.prompt, req.result);
            return true;
        }
        final boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && launchCapture(req.prompt, req.result, req.captureCategory)) {
            return true;
        }
        if (!granted) {
            toast("Permission denied — choosing an existing file instead");
        }
        launchDocumentOrMediaPicker(req.prompt, req.result, req.types, req.multiple);
        return true;
    }

    private static String permissionForCapture(int category) {
        if (category == UploadFiles.CAPTURE_AUDIO) return Manifest.permission.RECORD_AUDIO;
        return Manifest.permission.CAMERA;
    }

    private boolean launchCapture(FilePrompt prompt, GeckoResult<PromptResponse> result, int category) {
        final int slot = nextSlot();
        File tempCapture = null;
        final Intent intent;
        if (category == UploadFiles.CAPTURE_AUDIO) {
            intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
        } else {
            final boolean isVideo = category == UploadFiles.CAPTURE_VIDEO;
            intent = new Intent(isVideo
                    ? MediaStore.ACTION_VIDEO_CAPTURE
                    : MediaStore.ACTION_IMAGE_CAPTURE);
            try {
                tempCapture = createCaptureFile(activity.getApplicationContext(),
                        isVideo ? "video.mp4" : "photo.jpg");
                final Uri outputUri = UploadFileProvider.uriForCaptureFile(
                        activity.getApplicationContext(), tempCapture);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "could not prepare capture output file", e);
                deleteQuietly(tempCapture);
                return false;
            }
            if (prompt.capture == FilePrompt.Capture.USER) {
                intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
                intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
                intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
            }
        }

        if (!launch(intent, slot)) {
            deleteQuietly(tempCapture);
            return false;
        }
        pendingPrompt = prompt;
        pendingResult = result;
        pendingSlot = slot;
        pendingCaptureFile = tempCapture;
        return true;
    }

    private void launchDocumentOrMediaPicker(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                             String[] types, boolean multiple) {
        final int slot = nextSlot();
        boolean started = false;

        if (Build.VERSION.SDK_INT >= 33 && UploadFiles.isVisualMediaOnly(types)) {
            started = launch(photoPickerIntent(types, multiple), slot);
        }
        if (!started) {
            started = launch(pickerIntent(Intent.ACTION_OPEN_DOCUMENT, types, multiple), slot);
        }
        if (!started) {
            started = launch(pickerIntent(Intent.ACTION_GET_CONTENT, types, multiple), slot);
        }
        if (!started) {
            toast("No app available to choose files");
            completeDismissed(prompt, result);
            return;
        }
        pendingPrompt = prompt;
        pendingResult = result;
        pendingSlot = slot;
        pendingCaptureFile = null;
    }

    private int nextSlot() {
        final int slot = nextSlot;
        nextSlot = (nextSlot + 1) % SLOTS;
        return slot;
    }

    private static Intent photoPickerIntent(String[] types, boolean multiple) {
        final Intent intent = new Intent("android.provider.action.PICK_IMAGES");
        final boolean hasImg = UploadFiles.hasImageTypes(types);
        final boolean hasVid = UploadFiles.hasVideoTypes(types);
        if (hasImg && !hasVid) intent.setType("image/*");
        else if (hasVid && !hasImg) intent.setType("video/*");
        if (multiple) {
            intent.putExtra("android.provider.extra.PICK_IMAGES_MAX",
                    MediaStore.getPickImagesMaxLimit());
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static Intent pickerIntent(String action, String[] types, boolean multiple) {
        final Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (types == null) {
            intent.setType("*/*");
        } else if (types.length == 1) {
            intent.setType(types[0]);
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        }
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    private boolean launch(Intent intent, int slot) {
        try {
            activity.startActivityForResult(intent, REQ_BASE + slot);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "file picker could not start: " + intent.getAction(), e);
            return false;
        }
    }

    private static UploadFiles.ExtensionResolver platformResolver() {
        return ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    // ------------------------------------------------------------------
    // The user chose (or cancelled)
    // ------------------------------------------------------------------

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode < REQ_BASE || requestCode >= REQ_BASE + SLOTS) return false;

        if (pendingPrompt == null) {
            if (resultCode == Activity.RESULT_OK && data != null
                    && (data.getData() != null || data.getClipData() != null)) {
                toast("Browser restarted while choosing the file \u2014 please attach it again");
            }
            return true;
        }
        if (requestCode != REQ_BASE + pendingSlot) return true;

        final FilePrompt prompt = pendingPrompt;
        final GeckoResult<PromptResponse> result = pendingResult;
        final File captureFile = pendingCaptureFile;
        pendingPrompt = null;
        pendingResult = null;
        pendingCaptureFile = null;

        final List<Uri> picked = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK) {
            if (data != null) {
                if (data.getData() != null) picked.add(data.getData());
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        final Uri u = data.getClipData().getItemAt(i).getUri();
                        if (u != null && !picked.contains(u)) picked.add(u);
                    }
                }
            }
            if (picked.isEmpty() && captureFile != null
                    && captureFile.isFile() && captureFile.length() > 0) {
                picked.add(Uri.fromFile(captureFile));
            }
        }

        if (captureFile != null && (picked.isEmpty()
                || !"file".equals(picked.get(0).getScheme()))) {
            deleteQuietly(captureFile);
        }

        Log.i(TAG, "file picker answered: resultCode=" + resultCode + " files=" + picked.size());
        if (picked.isEmpty()) {
            completeDismissed(prompt, result);
            return true;
        }

        final Context appContext = activity.getApplicationContext();
        final ContentResolver cr = activity.getContentResolver();
        boolean big = false;
        final List<OpenedSource> openedSources = new ArrayList<>(picked.size());

        // Acquire streams synchronously while the Intent's transient URI grant is active.
        for (Uri u : picked) {
            if (sizeOf(cr, u) >= BIG_FILE_BYTES) big = true;
            if (ContentResolver.SCHEME_FILE.equals(u.getScheme())) {
                openedSources.add(new OpenedSource(u, null, null, null, null));
            } else {
                try {
                    if (ContentResolver.SCHEME_CONTENT.equals(u.getScheme())) {
                        cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                } catch (RuntimeException ignored) {
                }
                final String name = displayName(cr, u);
                final String mime = cr.getType(u);
                final String[] errHolder = new String[1];
                final ResolvedStream stream = openBestStream(cr, u, errHolder);
                openedSources.add(new OpenedSource(u, stream, name, mime, errHolder[0]));
            }
        }
        if (big) toast("Preparing file\u2026");

        worker.execute(() -> {
            final List<Uri> copies = new ArrayList<>();
            int failed = 0;
            String lastError = null;
            for (OpenedSource src : openedSources) {
                if (ContentResolver.SCHEME_FILE.equals(src.source.getScheme())) {
                    final Uri copy = copyLocalFileToCache(appContext, src.source);
                    if (copy != null) copies.add(copy);
                    else {
                        failed++;
                        lastError = "unreadable local file";
                    }
                } else if (src.stream == null) {
                    failed++;
                    lastError = src.openError != null ? src.openError : "could not open file";
                } else {
                    final String[] writeErr = new String[1];
                    final Uri copy = writeStreamToCache(
                            appContext, src.stream, src.name, src.mime, writeErr);
                    if (copy != null) copies.add(copy);
                    else {
                        failed++;
                        lastError = writeErr[0] != null ? writeErr[0] : "could not copy file";
                    }
                }
            }
            final int failedCount = failed;
            final String err = lastError;
            activity.runOnUiThread(() -> deliverOrQueue(prompt, result, copies, failedCount, err));
        });
        return true;
    }

    private void deliverOrQueue(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                List<Uri> copies, int failed, String lastError) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            completeDismissed(prompt, result);
            return;
        }
        if (!activityResumed) {
            pendingDelivery = new PendingDelivery(prompt, result, copies, failed, lastError);
            return;
        }
        deliver(prompt, result, copies, failed, lastError);
    }

    /** UI thread: hands the copies to GeckoView using the exact single/multi-URI confirm overload. */
    private void deliver(FilePrompt prompt, GeckoResult<PromptResponse> result,
                         List<Uri> copies, int failed, String lastError) {
        try {
            if (copies.isEmpty()) {
                toast(lastError != null && !lastError.isEmpty()
                        ? "Couldn't attach the file: " + lastError
                        : "Couldn't attach the file");
                completeDismissed(prompt, result);
                return;
            }
            if (prompt.type == FilePrompt.Type.MULTIPLE) {
                result.complete(prompt.confirm(activity, copies.toArray(new Uri[0])));
                Log.i(TAG, "file(s) handed to the page: " + copies.size());
            } else {
                result.complete(prompt.confirm(activity, copies.get(0)));
                Log.i(TAG, "file handed to the page: 1");
            }
            if (failed > 0) {
                toast(failed == 1 ? "1 file couldn't be attached"
                        : failed + " files couldn't be attached");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "attaching the file failed", e);
            toast("Couldn't attach the file: " + e.getClass().getSimpleName());
            completeDismissed(prompt, result);
        }
    }

    private static void completeDismissed(FilePrompt prompt, GeckoResult<PromptResponse> result) {
        try {
            result.complete(prompt.dismiss());
        } catch (RuntimeException ignored) {
        }
    }

    void dismissPending() {
        final FilePrompt prompt = pendingPrompt;
        final GeckoResult<PromptResponse> result = pendingResult;
        pendingPrompt = null;
        pendingResult = null;
        deleteQuietly(pendingCaptureFile);
        pendingCaptureFile = null;

        final PendingCaptureRequest permReq = pendingPermissionCapture;
        pendingPermissionCapture = null;
        if (permReq != null) completeDismissed(permReq.prompt, permReq.result);

        final PendingDelivery queued = pendingDelivery;
        pendingDelivery = null;
        if (queued != null) completeDismissed(queued.prompt, queued.result);

        if (prompt != null && result != null) completeDismissed(prompt, result);
    }

    // ------------------------------------------------------------------
    // Copies in the cache
    // ------------------------------------------------------------------

    private static Uri copyLocalFileToCache(Context context, Uri source) {
        File dir = null;
        try {
            final String path = source.getPath();
            if (path == null) return null;
            final File existing = new File(path);
            if (!existing.isFile() || !existing.canRead()) return null;
            final File uploadRoot = new File(context.getCacheDir(), UPLOAD_DIR).getCanonicalFile();
            if (existing.getCanonicalFile().getPath().startsWith(uploadRoot.getPath() + File.separator)) {
                return Uri.fromFile(existing);
            }
            dir = new File(uploadRoot, UUID.randomUUID().toString());
            if (!dir.mkdirs() && !dir.isDirectory()) return null;
            final File out = new File(dir, UploadFiles.safeFileName(existing.getName()));
            try (InputStream in = new FileInputStream(existing);
                 OutputStream os = new FileOutputStream(out)) {
                copyStream(in, os);
            }
            return Uri.fromFile(out);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not copy local file: " + source, e);
            if (dir != null) deleteRecursively(dir);
            return null;
        }
    }

    private static Uri writeStreamToCache(Context context, ResolvedStream resolved,
                                          String rawName, String fallbackMime, String[] errOut) {
        File dir = null;
        try {
            String name = UploadFiles.safeFileName(rawName);
            final String resolvedMime = resolved.mimeType != null ? resolved.mimeType : fallbackMime;
            if (resolvedMime != null) {
                name = UploadFiles.withExtension(name,
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime));
            }
            dir = new File(new File(context.getCacheDir(), UPLOAD_DIR), UUID.randomUUID().toString());
            if (!dir.mkdirs() && !dir.isDirectory()) {
                resolved.closeQuietly();
                if (errOut != null) errOut[0] = "cache dir unavailable";
                return null;
            }
            final File out = new File(dir, name);
            try (ResolvedStream stream = resolved;
                 OutputStream os = new FileOutputStream(out)) {
                copyStream(stream.inputStream, os);
            }
            return Uri.fromFile(out);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not write stream to cache", e);
            if (errOut != null) errOut[0] = e.getClass().getSimpleName();
            if (dir != null) deleteRecursively(dir);
            return null;
        }
    }

    static ResolvedStream openBestStream(ContentResolver cr, Uri source) {
        return openBestStream(cr, source, null);
    }

    static ResolvedStream openBestStream(ContentResolver cr, Uri source, String[] errOut) {
        Throwable lastErr = null;
        // Tier 1: standard openInputStream
        try {
            final InputStream direct = cr.openInputStream(source);
            if (direct != null) return new ResolvedStream(direct, null, null);
        } catch (Throwable e) {
            lastErr = e;
            Log.w(TAG, "openInputStream failed, trying typed/PFD fallback: " + source, e);
        }

        // Tier 2: virtual/cloud documents via openTypedAssetFileDescriptor
        try {
            final String[] streamTypes = cr.getStreamTypes(source, "*/*");
            final String targetMime = (streamTypes != null && streamTypes.length > 0)
                    ? streamTypes[0] : "*/*";
            final AssetFileDescriptor afd = cr.openTypedAssetFileDescriptor(source, targetMime, null);
            if (afd != null) {
                final InputStream in = afd.createInputStream();
                if (in != null) {
                    return new ResolvedStream(in, targetMime.equals("*/*") ? null : targetMime, afd);
                }
                afd.close();
            }
        } catch (Throwable e) {
            lastErr = e;
            Log.w(TAG, "openTypedAssetFileDescriptor failed: " + source, e);
        }

        // Tier 3: raw ParcelFileDescriptor fallback
        try {
            final ParcelFileDescriptor pfd = cr.openFileDescriptor(source, "r");
            if (pfd != null) {
                return new ResolvedStream(new ParcelFileDescriptor.AutoCloseInputStream(pfd), null, null);
            }
        } catch (Throwable e) {
            lastErr = e;
            Log.w(TAG, "openFileDescriptor failed: " + source, e);
        }

        if (errOut != null && lastErr != null) {
            errOut[0] = lastErr.getClass().getSimpleName();
        }
        return null;
    }

    static final class ResolvedStream implements AutoCloseable {
        final InputStream inputStream;
        final String mimeType;
        private final AssetFileDescriptor afd;

        ResolvedStream(InputStream inputStream, String mimeType, AssetFileDescriptor afd) {
            this.inputStream = inputStream;
            this.mimeType = mimeType;
            this.afd = afd;
        }

        @Override
        public void close() throws IOException {
            try {
                inputStream.close();
            } finally {
                if (afd != null) afd.close();
            }
        }

        void closeQuietly() {
            try { close(); } catch (IOException ignored) {}
        }
    }

    private static void copyStream(InputStream in, OutputStream os) throws IOException {
        final byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static File createCaptureFile(Context context, String fileName) throws IOException {
        final File dir = new File(UploadFileProvider.captureRoot(context), UUID.randomUUID().toString());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create capture directory: " + dir);
        }
        return new File(dir, fileName);
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                final String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "no display name for the chosen file", e);
        } finally {
            if (c != null) c.close();
        }
        return uri.getLastPathSegment();
    }

    private static long sizeOf(ContentResolver cr, Uri uri) {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private void deleteOldCopies() {
        try {
            final File root = new File(activity.getApplicationContext().getCacheDir(), UPLOAD_DIR);
            final File[] children = root.listFiles();
            if (children == null) return;
            final long cutoff = System.currentTimeMillis() - KEEP_COPIES_MS;
            for (File f : children) {
                if (f.lastModified() < cutoff) deleteRecursively(f);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "old upload copies could not be cleaned up", e);
        }
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        final File parent = f.getParentFile();
        if (!f.delete() && f.exists()) Log.w(TAG, "could not delete " + f);
        if (parent != null && parent.isDirectory()) {
            final String[] remaining = parent.list();
            if (remaining != null && remaining.length == 0) parent.delete();
        }
    }

    private static void deleteRecursively(File f) {
        final File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        if (!f.delete()) Log.w(TAG, "could not delete " + f);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
