package com.spoongecko.browser;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import android.os.Parcelable;
import android.os.SystemClock;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * [UPLOAD] Attaching local or captured files to a web page ({@code <input type=file>}),
 * combining Firefox for Android's picker/capture flow with GeckoView 160's file-path requirement:
 *  - Uses {@link Intent#ACTION_GET_CONTENT} wrapped in {@link Intent#createChooser} with
 *    {@link Intent#EXTRA_INITIAL_INTENTS} for camera/camcorder/audio capture when eligible;
 *  - Launches capture directly when HTML {@code capture} is requested, after checking runtime
 *    camera/audio permissions (with graceful fallback to the file chooser if denied);
 *  - Acquires selected {@code content://} streams synchronously inside {@link #handleActivityResult}
 *    while transient Intent read grants are active, copies them off the UI thread into
 *    {@code cache/uploads/<uuid>/}, and passes readable {@code file://} URIs to
 *    {@link FilePrompt#confirm} (because GeckoView 160 only resolves {@code file://}, raw
 *    {@code ExternalStorageProvider} paths, or legacy {@code _data} columns—failing silently on
 *    modern Android Photo Picker, Downloads, MediaStore, and cloud {@code content://} URIs);
 *  - Queues confirmation until {@link #onResume()} if copying completes before the Activity and
 *    active {@link org.mozilla.geckoview.GeckoSession} return to the foreground.
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
    private static final long CLEANUP_INTERVAL_MS = 60L * 60 * 1000;
    private static final long BIG_FILE_BYTES = 8L * 1024 * 1024;

    private final Activity activity;
    private final ExecutorService worker;
    private long lastCleanupMs;

    // UI thread only.
    private boolean activityResumed = true;
    private FilePrompt pendingPrompt;
    private GeckoResult<PromptResponse> pendingResult;
    private int pendingSlot;
    private int nextSlot;
    private File pendingImageFile;
    private Uri pendingImageUri;
    private File pendingVideoFile;
    private Uri pendingVideoUri;
    private PendingPermissionRequest pendingPermissionRequest;
    private PendingDelivery pendingDelivery;
    private boolean askedCameraForChooser;

    private static final class PendingPermissionRequest {
        final FilePrompt prompt;
        final GeckoResult<PromptResponse> result;
        final String[] types;
        final boolean multiple;
        final boolean directCapture;
        final int captureCategory;

        PendingPermissionRequest(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                 String[] types, boolean multiple,
                                 boolean directCapture, int captureCategory) {
            this.prompt = prompt;
            this.result = result;
            this.types = types;
            this.multiple = multiple;
            this.directCapture = directCapture;
            this.captureCategory = captureCategory;
        }
    }

    private static final class PendingDelivery {
        final FilePrompt prompt;
        final GeckoResult<PromptResponse> result;
        final List<Uri> localUris;
        final int failed;
        final String lastError;

        PendingDelivery(FilePrompt prompt, GeckoResult<PromptResponse> result,
                        List<Uri> localUris, int failed, String lastError) {
            this.prompt = prompt;
            this.result = result;
            this.localUris = localUris;
            this.failed = failed;
            this.lastError = lastError;
        }
    }

    private static final class OpenedSource {
        final Uri source;
        final Uri readyLocalUri;
        final ResolvedStream stream;
        final String name;
        final String mime;
        final String openError;

        OpenedSource(Uri source, Uri readyLocalUri, ResolvedStream stream,
                     String name, String mime, String openError) {
            this.source = source;
            this.readyLocalUri = readyLocalUri;
            this.stream = stream;
            this.name = name;
            this.mime = mime;
            this.openError = openError;
        }
    }

    FilePicker(Activity activity) {
        this.activity = activity;
        final ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            final Thread t = new Thread(r, "spoongecko-upload");
            t.setDaemon(true);
            return t;
        });
        exec.allowCoreThreadTimeOut(true);
        this.worker = exec;
        this.lastCleanupMs = SystemClock.elapsedRealtime();
        worker.execute(this::deleteOldUploads);
        relaxPickerGate();
    }

    void onResume() {
        activityResumed = true;
        final PendingDelivery delivery = pendingDelivery;
        if (delivery != null) {
            pendingDelivery = null;
            deliver(delivery.prompt, delivery.result, delivery.localUris,
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
            completeDismissed(prompt, result);
            return result;
        }
        if (prompt.type == FilePrompt.Type.FOLDER) {
            toast("Uploading a whole folder isn't supported");
            completeDismissed(prompt, result);
            return result;
        }

        dismissPending();
        final long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastCleanupMs >= CLEANUP_INTERVAL_MS) {
            lastCleanupMs = nowMs;
            worker.execute(this::deleteOldUploads);
        }

        final boolean multiple = prompt.type == FilePrompt.Type.MULTIPLE;
        final String[] types = UploadFiles.acceptedTypes(prompt.mimeTypes, platformResolver());

        if (prompt.capture != FilePrompt.Capture.NONE) {
            final int category = UploadFiles.captureCategory(types);
            final String neededPerm = permissionForCapture(category);
            if (neededPerm != null && !hasPermission(neededPerm)) {
                pendingPermissionRequest = new PendingPermissionRequest(
                        prompt, result, types, multiple, true, category);
                activity.requestPermissions(new String[]{neededPerm}, REQ_CAPTURE_PERM);
                return result;
            }
            if (launchDirectCapture(prompt, result, category)) {
                return result;
            }
        }

        // For explicit image/video inputs (capture == NONE), ask once for CAMERA so the chooser can
        // offer "Take photo / video" alongside Files/Gallery — exact Firefox for Android UX.
        if (types != null
                && (UploadFiles.hasImageTypes(types) || UploadFiles.hasVideoTypes(types))
                && !hasPermission(Manifest.permission.CAMERA)
                && !askedCameraForChooser) {
            askedCameraForChooser = true;
            pendingPermissionRequest = new PendingPermissionRequest(
                    prompt, result, types, multiple, false, UploadFiles.CAPTURE_IMAGE);
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAPTURE_PERM);
            return result;
        }

        launchChooserOrPicker(prompt, result, types, multiple);
        return result;
    }

    boolean handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_CAPTURE_PERM) return false;
        final PendingPermissionRequest req = pendingPermissionRequest;
        pendingPermissionRequest = null;
        if (req == null) return true;
        if (activity.isFinishing() || activity.isDestroyed()) {
            completeDismissed(req.prompt, req.result);
            return true;
        }
        final boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (req.directCapture) {
            if (granted && launchDirectCapture(req.prompt, req.result, req.captureCategory)) {
                return true;
            }
            if (!granted) {
                toast("Permission denied \u2014 choosing an existing file instead");
            }
        }
        launchChooserOrPicker(req.prompt, req.result, req.types, req.multiple);
        return true;
    }

    private boolean hasPermission(String perm) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String permissionForCapture(int category) {
        if (category == UploadFiles.CAPTURE_AUDIO) return Manifest.permission.RECORD_AUDIO;
        return Manifest.permission.CAMERA;
    }

    private boolean launchDirectCapture(FilePrompt prompt, GeckoResult<PromptResponse> result, int category) {
        final int slot = nextSlot();
        final Context appContext = activity.getApplicationContext();
        File imgFile = null;
        Uri imgUri = null;
        File vidFile = null;
        Uri vidUri = null;
        final Intent intent;

        if (category == UploadFiles.CAPTURE_AUDIO) {
            intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
        } else if (category == UploadFiles.CAPTURE_VIDEO) {
            try {
                vidFile = createCaptureFile(appContext, "video-" + UUID.randomUUID() + ".mp4");
                vidUri = UploadFileProvider.uriForCaptureFile(appContext, vidFile);
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "could not prepare video capture file", e);
                deleteQuietly(vidFile);
                return false;
            }
            intent = buildVideoCaptureIntent(prompt.capture, vidUri);
        } else {
            try {
                imgFile = createCaptureFile(appContext, "photo-" + UUID.randomUUID() + ".jpg");
                imgUri = UploadFileProvider.uriForCaptureFile(appContext, imgFile);
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "could not prepare image capture file", e);
                deleteQuietly(imgFile);
                return false;
            }
            intent = buildImageCaptureIntent(prompt.capture, imgUri);
        }

        if (!launch(intent, slot)) {
            deleteQuietly(imgFile);
            deleteQuietly(vidFile);
            return false;
        }
        pendingPrompt = prompt;
        pendingResult = result;
        pendingSlot = slot;
        pendingImageFile = imgFile;
        pendingImageUri = imgUri;
        pendingVideoFile = vidFile;
        pendingVideoUri = vidUri;
        return true;
    }

    private void launchChooserOrPicker(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                       String[] types, boolean multiple) {
        final int slot = nextSlot();
        final Context appContext = activity.getApplicationContext();
        final PackageManager pm = activity.getPackageManager();

        final Intent baseGetContent = buildGetContentIntent(types, multiple);
        final List<Intent> extraCaptureIntents = new ArrayList<>();

        File imgFile = null;
        Uri imgUri = null;
        File vidFile = null;
        Uri vidUri = null;

        if (UploadFiles.allowsImage(types) && hasPermission(Manifest.permission.CAMERA)) {
            try {
                imgFile = createCaptureFile(appContext, "photo-" + UUID.randomUUID() + ".jpg");
                imgUri = UploadFileProvider.uriForCaptureFile(appContext, imgFile);
                final Intent cam = buildImageCaptureIntent(prompt.capture, imgUri);
                if (cam.resolveActivity(pm) != null) {
                    extraCaptureIntents.add(cam);
                } else {
                    deleteQuietly(imgFile);
                    imgFile = null;
                    imgUri = null;
                }
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "could not add image capture to chooser", e);
                deleteQuietly(imgFile);
                imgFile = null;
                imgUri = null;
            }
        }

        if (UploadFiles.allowsVideo(types) && hasPermission(Manifest.permission.CAMERA)) {
            try {
                vidFile = createCaptureFile(appContext, "video-" + UUID.randomUUID() + ".mp4");
                vidUri = UploadFileProvider.uriForCaptureFile(appContext, vidFile);
                final Intent vid = buildVideoCaptureIntent(prompt.capture, vidUri);
                if (vid.resolveActivity(pm) != null) {
                    extraCaptureIntents.add(vid);
                } else {
                    deleteQuietly(vidFile);
                    vidFile = null;
                    vidUri = null;
                }
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "could not add video capture to chooser", e);
                deleteQuietly(vidFile);
                vidFile = null;
                vidUri = null;
            }
        }

        if (UploadFiles.allowsAudio(types) && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            final Intent audio = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
            if (audio.resolveActivity(pm) != null) {
                extraCaptureIntents.add(audio);
            }
        }

        final CharSequence title = (prompt.title != null && !prompt.title.trim().isEmpty())
                ? prompt.title : null;
        final Intent chooser = Intent.createChooser(baseGetContent, title);
        if (!extraCaptureIntents.isEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                    extraCaptureIntents.toArray(new Parcelable[0]));
        }

        boolean started = launch(chooser, slot);
        if (!started) {
            deleteQuietly(imgFile);
            deleteQuietly(vidFile);
            imgFile = null;
            imgUri = null;
            vidFile = null;
            vidUri = null;
            started = launch(baseGetContent, slot);
        }
        if (!started) {
            started = launch(buildOpenDocumentIntent(types, multiple), slot);
        }
        if (!started) {
            toast("No app available to choose files");
            completeDismissed(prompt, result);
            return;
        }

        pendingPrompt = prompt;
        pendingResult = result;
        pendingSlot = slot;
        pendingImageFile = imgFile;
        pendingImageUri = imgUri;
        pendingVideoFile = vidFile;
        pendingVideoUri = vidUri;
    }

    private int nextSlot() {
        final int slot = nextSlot;
        nextSlot = (nextSlot + 1) % SLOTS;
        return slot;
    }

    private static Intent buildGetContentIntent(String[] types, boolean multiple) {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        applyMimeTypes(intent, types);
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return intent;
    }

    private static Intent buildOpenDocumentIntent(String[] types, boolean multiple) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        applyMimeTypes(intent, types);
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return intent;
    }

    private static void applyMimeTypes(Intent intent, String[] types) {
        if (types == null || types.length == 0) {
            intent.setType("*/*");
        } else if (types.length == 1) {
            intent.setType(types[0]);
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        }
    }

    private static Intent buildImageCaptureIntent(int captureFacing, Uri outputUri) {
        final Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        intent.setClipData(ClipData.newRawUri("", outputUri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        applyCameraFacing(intent, captureFacing);
        return intent;
    }

    private static Intent buildVideoCaptureIntent(int captureFacing, Uri outputUri) {
        final Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        intent.setClipData(ClipData.newRawUri("", outputUri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        applyCameraFacing(intent, captureFacing);
        return intent;
    }

    private static void applyCameraFacing(Intent intent, int captureFacing) {
        if (captureFacing == FilePrompt.Capture.USER) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
        } else if (captureFacing == FilePrompt.Capture.ENVIRONMENT) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 0);
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 0);
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", false);
        }
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

        if (pendingPrompt == null || pendingResult == null) {
            if (resultCode == Activity.RESULT_OK && data != null
                    && (data.getData() != null || data.getClipData() != null)) {
                toast("Browser restarted while choosing the file \u2014 please attach it again");
            }
            return true;
        }
        if (requestCode != REQ_BASE + pendingSlot) return true;

        final FilePrompt prompt = pendingPrompt;
        final GeckoResult<PromptResponse> result = pendingResult;
        final File imgFile = pendingImageFile;
        final Uri imgUri = pendingImageUri;
        final File vidFile = pendingVideoFile;
        final Uri vidUri = pendingVideoUri;
        clearPendingRefs();

        if (resultCode != Activity.RESULT_OK) {
            deleteQuietly(imgFile);
            deleteQuietly(vidFile);
            completeDismissed(prompt, result);
            return true;
        }

        final Context appContext = activity.getApplicationContext();
        final List<Uri> picked = new ArrayList<>();

        if (data != null) {
            final ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    final Uri u = clip.getItemAt(i).getUri();
                    if (u != null && !picked.contains(u)) picked.add(u);
                }
            }
            if (picked.isEmpty() && data.getData() != null) {
                picked.add(data.getData());
            }
        }

        // Camera / camcorder apps writing to EXTRA_OUTPUT often return data == null or data.getData() == null.
        boolean usedImgCapture = false;
        boolean usedVidCapture = false;
        if (picked.isEmpty()) {
            if (imgFile != null && imgFile.isFile() && imgFile.length() > 0 && imgUri != null) {
                picked.add(imgUri);
                usedImgCapture = true;
            } else if (vidFile != null && vidFile.isFile() && vidFile.length() > 0 && vidUri != null) {
                picked.add(vidUri);
                usedVidCapture = true;
            }
        } else {
            if (imgUri != null && picked.contains(imgUri)
                    && imgFile != null && imgFile.isFile() && imgFile.length() > 0) {
                usedImgCapture = true;
            }
            if (vidUri != null && picked.contains(vidUri)
                    && vidFile != null && vidFile.isFile() && vidFile.length() > 0) {
                usedVidCapture = true;
            }
        }

        if (!usedImgCapture) deleteQuietly(imgFile);
        if (!usedVidCapture) deleteQuietly(vidFile);

        if (picked.isEmpty()) {
            completeDismissed(prompt, result);
            return true;
        }

        if (prompt.type != FilePrompt.Type.MULTIPLE && picked.size() > 1) {
            picked.subList(1, picked.size()).clear();
        }

        final boolean takePersistable = data != null
                && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        final ContentResolver cr = appContext.getContentResolver();
        final List<OpenedSource> openedSources = new ArrayList<>(picked.size());
        boolean big = false;

        // Acquire source streams synchronously inside onActivityResult while transient Intent URI
        // read grants are guaranteed active, then copy bytes off the UI thread to cache/uploads/
        // so GeckoView 160 receives directly readable file:// URIs.
        for (Uri u : picked) {
            if (usedImgCapture && u.equals(imgUri) && imgFile != null && imgFile.canRead()) {
                if (imgFile.length() >= BIG_FILE_BYTES) big = true;
                openedSources.add(new OpenedSource(u, Uri.fromFile(imgFile), null, null, null, null));
                continue;
            }
            if (usedVidCapture && u.equals(vidUri) && vidFile != null && vidFile.canRead()) {
                if (vidFile.length() >= BIG_FILE_BYTES) big = true;
                openedSources.add(new OpenedSource(u, Uri.fromFile(vidFile), null, null, null, null));
                continue;
            }

            if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(u.getScheme())) {
                final String path = u.getPath();
                final File f = path != null ? new File(path) : null;
                if (f != null && f.isFile() && f.length() >= BIG_FILE_BYTES) big = true;
                openedSources.add(new OpenedSource(u, null, null, null, null, null));
            } else {
                if (takePersistable && ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(u.getScheme())) {
                    try {
                        cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (RuntimeException ignored) {
                    }
                }
                final UriMeta meta = queryMeta(cr, u);
                if (meta.size >= BIG_FILE_BYTES) big = true;
                String mime = null;
                try {
                    mime = cr.getType(u);
                } catch (RuntimeException ignored) {
                }
                final String[] errHolder = new String[1];
                final ResolvedStream stream = openBestStream(cr, u, errHolder);
                openedSources.add(new OpenedSource(u, null, stream, meta.name, mime, errHolder[0]));
            }
        }
        if (big) toast("Preparing file\u2026");

        worker.execute(() -> {
            final List<Uri> localUris = new ArrayList<>(openedSources.size());
            int failed = 0;
            String lastError = null;

            for (OpenedSource src : openedSources) {
                if (src.readyLocalUri != null) {
                    localUris.add(src.readyLocalUri);
                } else if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(src.source.getScheme())) {
                    final Uri copy = copyLocalFileToCache(appContext, cr, src.source);
                    if (copy != null) {
                        localUris.add(copy);
                    } else {
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
                    if (copy != null) {
                        localUris.add(copy);
                    } else {
                        failed++;
                        lastError = writeErr[0] != null ? writeErr[0] : "could not copy file";
                    }
                }
            }

            final int failedCount = failed;
            final String err = lastError;
            activity.runOnUiThread(() -> deliverOrQueue(prompt, result, localUris, failedCount, err));
        });
        return true;
    }

    private void deliverOrQueue(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                List<Uri> localUris, int failed, String lastError) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            completeDismissed(prompt, result);
            return;
        }
        if (prompt.isComplete()) {
            return;
        }
        if (!activityResumed) {
            pendingDelivery = new PendingDelivery(prompt, result, localUris, failed, lastError);
            return;
        }
        deliver(prompt, result, localUris, failed, lastError);
    }

    private void deliver(FilePrompt prompt, GeckoResult<PromptResponse> result,
                         List<Uri> localUris, int failed, String lastError) {
        if (prompt.isComplete()) return;
        try {
            if (localUris.isEmpty()) {
                toast(lastError != null && !lastError.isEmpty()
                        ? "Couldn't attach the file: " + lastError
                        : "Couldn't attach the file");
                completeDismissed(prompt, result);
                return;
            }
            Log.i(TAG, "file picker confirming " + localUris.size() + " local URI(s)");
            if (prompt.type == FilePrompt.Type.MULTIPLE) {
                result.complete(prompt.confirm(activity.getApplicationContext(),
                        localUris.toArray(new Uri[0])));
            } else {
                result.complete(prompt.confirm(activity.getApplicationContext(), localUris.get(0)));
            }
            if (failed > 0) {
                toast(failed == 1 ? "1 file couldn't be attached"
                        : failed + " files couldn't be attached");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "FilePrompt.confirm failed", e);
            toast("Couldn't attach the file: " + e.getClass().getSimpleName());
            completeDismissed(prompt, result);
        }
    }

    private static void completeDismissed(FilePrompt prompt, GeckoResult<PromptResponse> result) {
        try {
            if (!prompt.isComplete()) {
                result.complete(prompt.dismiss());
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void clearPendingRefs() {
        pendingPrompt = null;
        pendingResult = null;
        pendingImageFile = null;
        pendingImageUri = null;
        pendingVideoFile = null;
        pendingVideoUri = null;
    }

    void dismissPending() {
        final FilePrompt prompt = pendingPrompt;
        final GeckoResult<PromptResponse> result = pendingResult;
        deleteQuietly(pendingImageFile);
        deleteQuietly(pendingVideoFile);
        clearPendingRefs();

        final PendingPermissionRequest permReq = pendingPermissionRequest;
        pendingPermissionRequest = null;
        if (permReq != null) completeDismissed(permReq.prompt, permReq.result);

        final PendingDelivery queued = pendingDelivery;
        pendingDelivery = null;
        if (queued != null) completeDismissed(queued.prompt, queued.result);

        if (prompt != null && result != null) completeDismissed(prompt, result);
    }

    // ------------------------------------------------------------------
    // Cache copies for GeckoView FilePrompt (requires readable POSIX paths)
    // ------------------------------------------------------------------

    private static File uploadRoot(Context context) {
        return new File(context.getCacheDir(), UPLOAD_DIR);
    }

    private static Uri copyLocalFileToCache(Context context, ContentResolver cr, Uri source) {
        File dir = null;
        try {
            final String path = source.getPath();
            if (path == null) return null;
            final File existing = new File(path);
            final File root = uploadRoot(context).getCanonicalFile();
            if (existing.isFile() && existing.canRead()) {
                if (existing.getCanonicalFile().getPath().startsWith(root.getPath() + File.separator)) {
                    return Uri.fromFile(existing);
                }
                dir = new File(root, UUID.randomUUID().toString());
                if (!dir.mkdirs() && !dir.isDirectory()) return null;
                final File out = new File(dir, UploadFiles.safeFileName(existing.getName()));
                try (InputStream in = new FileInputStream(existing);
                     OutputStream os = new FileOutputStream(out)) {
                    copyStream(in, os);
                }
                return Uri.fromFile(out);
            }
            // Fallback via ContentResolver if direct POSIX read fails
            final ResolvedStream stream = openBestStream(cr, source, null);
            if (stream != null) {
                return writeStreamToCache(context, stream, existing.getName(), null, null);
            }
            return null;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not copy local file: " + source, e);
            if (dir != null) deleteRecursively(dir);
            return null;
        }
    }

    private static Uri writeStreamToCache(Context context, ResolvedStream resolved,
                                          String rawName, String fallbackMime, String[] errOut) {
        File dir = null;
        try (ResolvedStream stream = resolved) {
            String name = UploadFiles.safeFileName(rawName);
            final String resolvedMime = resolved.mimeType != null ? resolved.mimeType : fallbackMime;
            if (resolvedMime != null && !"application/octet-stream".equalsIgnoreCase(resolvedMime)) {
                name = UploadFiles.withExtension(name,
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime));
            }
            dir = new File(uploadRoot(context), UUID.randomUUID().toString());
            if (!dir.mkdirs() && !dir.isDirectory()) {
                if (errOut != null) errOut[0] = "cache dir unavailable";
                return null;
            }
            final File out = new File(dir, name);
            try (OutputStream os = new FileOutputStream(out)) {
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

    private static void copyStream(InputStream in, OutputStream os) throws IOException {
        final byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static final class UriMeta {
        final String name;
        final long size;

        UriMeta(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static UriMeta queryMeta(ContentResolver cr, Uri uri) {
        String name = null;
        long size = -1L;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                final int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                    final String candidate = c.getString(nameIdx);
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        name = candidate;
                    }
                }
                final int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                    size = c.getLong(sizeIdx);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not query metadata for chosen file", e);
        } finally {
            if (c != null) c.close();
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return new UriMeta(name, size);
    }

    // ------------------------------------------------------------------
    // Shared stream helper (also used by ExtensionsActivity for .xpi / JSON)
    // ------------------------------------------------------------------

    static ResolvedStream openBestStream(ContentResolver cr, Uri source) {
        return openBestStream(cr, source, null);
    }

    static ResolvedStream openBestStream(ContentResolver cr, Uri source, String[] errOut) {
        Throwable lastErr = null;
        try {
            final InputStream direct = cr.openInputStream(source);
            if (direct != null) return new ResolvedStream(direct, null, null);
        } catch (Throwable e) {
            lastErr = e;
            Log.w(TAG, "openInputStream failed, trying typed/PFD fallback: " + source, e);
        }

        try {
            final String[] streamTypes = cr.getStreamTypes(source, "*/*");
            final String[] candidates = (streamTypes != null && streamTypes.length > 0)
                    ? streamTypes : new String[]{"*/*"};
            for (String targetMime : candidates) {
                if (targetMime == null || targetMime.isEmpty()) continue;
                try {
                    final AssetFileDescriptor afd =
                            cr.openTypedAssetFileDescriptor(source, targetMime, null);
                    if (afd != null) {
                        final InputStream in = afd.createInputStream();
                        if (in != null) {
                            return new ResolvedStream(in,
                                    "*/*".equals(targetMime) ? null : targetMime, afd);
                        }
                        afd.close();
                    }
                } catch (Throwable e) {
                    lastErr = e;
                    Log.w(TAG, "openTypedAssetFileDescriptor failed (" + targetMime + "): " + source, e);
                }
            }
        } catch (Throwable e) {
            lastErr = e;
            Log.w(TAG, "getStreamTypes failed: " + source, e);
        }

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
    }

    // ------------------------------------------------------------------
    // Temporary capture & upload cleanup
    // ------------------------------------------------------------------

    private static File createCaptureFile(Context context, String fileName) throws IOException {
        final File dir = new File(UploadFileProvider.captureRoot(context), UUID.randomUUID().toString());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create capture directory: " + dir);
        }
        final File file = new File(dir, fileName);
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.createNewFile();
        }
        return file;
    }

    private void deleteOldUploads() {
        final Context appContext = activity.getApplicationContext();
        final long cutoff = System.currentTimeMillis() - KEEP_COPIES_MS;
        deleteExpiredChildren(uploadRoot(appContext), "capture", cutoff);
        deleteExpiredChildren(UploadFileProvider.captureRoot(appContext), null, cutoff);
    }

    private static void deleteExpiredChildren(File dir, String skipName, long cutoff) {
        try {
            final File[] children = dir.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (skipName != null && skipName.equals(f.getName())) continue;
                if (f.lastModified() < cutoff) deleteRecursively(f);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "old upload files could not be cleaned up", e);
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
