package com.spoongecko.browser;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * [UPLOAD] Attaching local or captured files to a web page ({@code <input type=file>}):
 *  - Launches Storage Access Framework ({@link Intent#ACTION_OPEN_DOCUMENT}) and Android 13+
 *    Photo Picker ({@link MediaStore#ACTION_PICK_IMAGES}) directly without wrapping in
 *    {@link Intent#createChooser} / {@code EXTRA_INITIAL_INTENTS}, avoiding cross-process
 *    {@code IntentResolver} grant drops on Android 16/17 ({@code targetSdk 37});
 *  - Uses an in-app glass source menu when media capture (camera/camcorder/audio) or Photo
 *    Picker options are eligible alongside document browsing;
 *  - Acquires selected {@code content://} streams synchronously inside {@link #handleActivityResult}
 *    while transient Intent read grants are active (trying both Activity and Application
 *    {@link ContentResolver}s), copies them off the UI thread into {@code cache/uploads/<uuid>/},
 *    and confirms {@link FilePrompt} with staged {@code file://} URIs (with an internal
 *    {@link UploadFileProvider} {@code content://} fallback if {@code confirm} rejects {@code file://});
 *  - Preserves the pending prompt on permission/read failures and shows an interactive glass
 *    recovery dialog with one-tap fallback pickers and copyable diagnostic details.
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

    private enum PickerMode {
        OPEN_DOCUMENT("ACTION_OPEN_DOCUMENT"),
        PICK_IMAGES("ACTION_PICK_IMAGES"),
        GET_CONTENT("ACTION_GET_CONTENT"),
        SYSTEM_CHOOSER("ACTION_CHOOSER"),
        CAPTURE_IMAGE("ACTION_IMAGE_CAPTURE"),
        CAPTURE_VIDEO("ACTION_VIDEO_CAPTURE"),
        CAPTURE_AUDIO("RECORD_SOUND_ACTION");

        final String label;

        PickerMode(String label) {
            this.label = label;
        }
    }

    private final Activity activity;
    private final ExecutorService worker;
    private final List<Dialog> activeDialogs = new ArrayList<>();
    private long lastCleanupMs;

    // UI thread only.
    private boolean activityResumed = true;
    private FilePrompt pendingPrompt;
    private GeckoResult<PromptResponse> pendingResult;
    private String[] pendingTypes;
    private boolean pendingMultiple;
    private PickerMode pendingPickerMode;
    private int pendingSlot;
    private int nextSlot;
    private File pendingImageFile;
    private Uri pendingImageUri;
    private File pendingVideoFile;
    private Uri pendingVideoUri;
    private PendingPermissionRequest pendingPermissionRequest;
    private PendingDelivery pendingDelivery;

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
        final String[] types;
        final boolean multiple;
        final PickerMode pickerMode;
        final int intentFlags;
        final List<Uri> localUris;
        final int failed;
        final AttachmentDiagnostic diagnostic;

        PendingDelivery(FilePrompt prompt, GeckoResult<PromptResponse> result,
                        String[] types, boolean multiple, PickerMode pickerMode,
                        int intentFlags, List<Uri> localUris, int failed,
                        AttachmentDiagnostic diagnostic) {
            this.prompt = prompt;
            this.result = result;
            this.types = types;
            this.multiple = multiple;
            this.pickerMode = pickerMode;
            this.intentFlags = intentFlags;
            this.localUris = localUris;
            this.failed = failed;
            this.diagnostic = diagnostic;
        }
    }

    private static final class StreamError {
        String stage;
        String summary;
        Throwable cause;

        void record(String stage, Throwable cause) {
            if (this.cause instanceof SecurityException && !(cause instanceof SecurityException)) {
                return;
            }
            this.stage = stage;
            this.cause = cause;
            this.summary = cause != null ? cause.getClass().getSimpleName() : null;
        }
    }

    private static final class AttachmentDiagnostic {
        final String stage;
        final PickerMode pickerMode;
        final Uri sourceUri;
        final int intentFlags;
        final String summary;
        final Throwable cause;

        AttachmentDiagnostic(String stage, PickerMode pickerMode, Uri sourceUri,
                             int intentFlags, String summary, Throwable cause) {
            this.stage = stage;
            this.pickerMode = pickerMode;
            this.sourceUri = sourceUri;
            this.intentFlags = intentFlags;
            this.summary = summary;
            this.cause = cause;
        }

        boolean isSecurityError() {
            if (cause instanceof SecurityException) return true;
            return "SecurityException".equals(summary);
        }

        String formatReport(Context context) {
            final StringBuilder sb = new StringBuilder();
            sb.append("SpoonGecko attachment diagnostic\n");
            sb.append("time:      ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(new Date())).append('\n');
            sb.append("stage:     ").append(stage != null ? stage : "unknown").append('\n');
            sb.append("picker:    ").append(pickerMode != null ? pickerMode.label : "unknown").append('\n');
            sb.append("flags:     0x").append(Integer.toHexString(intentFlags)).append('\n');
            if (sourceUri != null) {
                sb.append("scheme:    ").append(nullSafe(sourceUri.getScheme())).append('\n');
                sb.append("authority: ").append(nullSafe(sourceUri.getAuthority())).append('\n');
            }
            final int targetSdk = context.getApplicationInfo().targetSdkVersion;
            sb.append("device:    ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append(" (Android ").append(Build.VERSION.RELEASE)
                    .append(", API ").append(Build.VERSION.SDK_INT)
                    .append(", targetSdk ").append(targetSdk).append(")\n");
            if (summary != null && !summary.isEmpty()) {
                sb.append("error:     ").append(summary).append('\n');
            }
            if (cause != null) {
                final StringWriter sw = new StringWriter();
                cause.printStackTrace(new PrintWriter(sw));
                sb.append("\n----- stack trace -----\n").append(sw);
            }
            return sb.toString();
        }

        private static String nullSafe(String s) {
            return s != null && !s.isEmpty() ? s : "(none)";
        }
    }

    private static final class OpenedSource {
        final Uri source;
        final Uri readyLocalUri;
        final ResolvedStream stream;
        final String name;
        final String mime;
        final String openStage;
        final String openError;
        final Throwable openCause;

        OpenedSource(Uri source, Uri readyLocalUri, ResolvedStream stream,
                     String name, String mime, String openStage,
                     String openError, Throwable openCause) {
            this.source = source;
            this.readyLocalUri = readyLocalUri;
            this.stream = stream;
            this.name = name;
            this.mime = mime;
            this.openStage = openStage;
            this.openError = openError;
            this.openCause = openCause;
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
            deliver(delivery.prompt, delivery.result, delivery.types, delivery.multiple,
                    delivery.pickerMode, delivery.intentFlags, delivery.localUris,
                    delivery.failed, delivery.diagnostic);
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
            if (launchDirectCapture(prompt, result, types, multiple, category)) {
                return result;
            }
        }

        launchInitialPickerOrMenu(prompt, result, types, multiple);
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
        if (granted && launchDirectCapture(req.prompt, req.result, req.types, req.multiple, req.captureCategory)) {
            return true;
        }
        if (!granted) {
            toast("Permission denied \u2014 choosing an existing file instead");
        }
        launchPrimaryDocumentPicker(req.prompt, req.result, req.types, req.multiple);
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

    private void launchInitialPickerOrMenu(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                           String[] types, boolean multiple) {
        final boolean mediaSpecific = types != null
                && (UploadFiles.hasImageTypes(types)
                || UploadFiles.hasVideoTypes(types)
                || UploadFiles.hasAudioTypes(types));
        if (mediaSpecific && showSourceChooserDialog(prompt, result, types, multiple)) {
            return;
        }
        launchPrimaryDocumentPicker(prompt, result, types, multiple);
    }

    private void launchPrimaryDocumentPicker(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                             String[] types, boolean multiple) {
        if (launchPickerMode(prompt, result, types, multiple, PickerMode.OPEN_DOCUMENT)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && UploadFiles.isVisualMediaOnly(types)
                && launchPickerMode(prompt, result, types, multiple, PickerMode.PICK_IMAGES)) {
            return;
        }
        if (launchPickerMode(prompt, result, types, multiple, PickerMode.GET_CONTENT)) return;
        if (launchPickerMode(prompt, result, types, multiple, PickerMode.SYSTEM_CHOOSER)) return;
        toast("No app available to choose files");
        completeDismissed(prompt, result);
    }

    private boolean launchDirectCapture(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                        String[] types, boolean multiple, int category) {
        final int slot = nextSlot();
        final Context appContext = activity.getApplicationContext();
        File imgFile = null;
        Uri imgUri = null;
        File vidFile = null;
        Uri vidUri = null;
        final Intent intent;
        final PickerMode mode;

        if (category == UploadFiles.CAPTURE_AUDIO) {
            intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
            mode = PickerMode.CAPTURE_AUDIO;
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
            mode = PickerMode.CAPTURE_VIDEO;
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
            mode = PickerMode.CAPTURE_IMAGE;
        }

        if (!launch(intent, slot)) {
            deleteQuietly(imgFile);
            deleteQuietly(vidFile);
            return false;
        }
        setPendingPicker(prompt, result, types, multiple, mode, slot, imgFile, imgUri, vidFile, vidUri);
        return true;
    }

    private boolean launchPickerMode(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                     String[] types, boolean multiple, PickerMode mode) {
        final int slot = nextSlot();
        final Intent intent;
        switch (mode) {
            case OPEN_DOCUMENT:
                intent = buildOpenDocumentIntent(types, multiple);
                break;
            case PICK_IMAGES:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
                intent = buildPhotoPickerIntent(types, multiple);
                if (intent == null) return false;
                break;
            case GET_CONTENT:
                intent = buildGetContentIntent(types, multiple);
                break;
            case SYSTEM_CHOOSER: {
                final CharSequence title = (prompt.title != null && !prompt.title.trim().isEmpty())
                        ? prompt.title : null;
                intent = Intent.createChooser(buildGetContentIntent(types, multiple), title);
                break;
            }
            default:
                return false;
        }

        if (!launch(intent, slot)) {
            return false;
        }
        setPendingPicker(prompt, result, types, multiple, mode, slot, null, null, null, null);
        return true;
    }

    private void setPendingPicker(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                  String[] types, boolean multiple, PickerMode mode, int slot,
                                  File imgFile, Uri imgUri, File vidFile, Uri vidUri) {
        pendingPrompt = prompt;
        pendingResult = result;
        pendingTypes = types;
        pendingMultiple = multiple;
        pendingPickerMode = mode;
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

    private static Intent buildOpenDocumentIntent(String[] types, boolean multiple) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        applyMimeTypes(intent, types);
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return intent;
    }

    private static Intent buildGetContentIntent(String[] types, boolean multiple) {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        applyMimeTypes(intent, types);
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return intent;
    }

    private static Intent buildPhotoPickerIntent(String[] types, boolean multiple) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        final Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        final boolean hasImg = UploadFiles.allowsImage(types);
        final boolean hasVid = UploadFiles.allowsVideo(types);
        if (hasImg && !hasVid) {
            intent.setType("image/*");
        } else if (hasVid && !hasImg) {
            intent.setType("video/*");
        }
        if (multiple) {
            final int max = MediaStore.getPickImagesMaxLimit();
            if (max > 1) {
                intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
            }
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

    private boolean canResolve(Intent intent) {
        if (intent == null) return false;
        try {
            return intent.resolveActivity(activity.getPackageManager()) != null;
        } catch (RuntimeException e) {
            return false;
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
    // In-app glass source chooser dialog (avoids Intent.createChooser middleman)
    // ------------------------------------------------------------------

    private static final class SourceOption {
        final String label;
        final Runnable action;

        SourceOption(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private boolean showSourceChooserDialog(final FilePrompt prompt,
                                            final GeckoResult<PromptResponse> result,
                                            final String[] types,
                                            final boolean multiple) {
        if (activity.isFinishing() || activity.isDestroyed()) return false;

        final List<SourceOption> options = new ArrayList<>();
        final boolean[] handedOff = {false};

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && (UploadFiles.allowsImage(types) || UploadFiles.allowsVideo(types))) {
            final Intent photoPicker = buildPhotoPickerIntent(types, multiple);
            if (canResolve(photoPicker)) {
                options.add(new SourceOption("Photos & videos", () -> {
                    handedOff[0] = true;
                    if (!launchPickerMode(prompt, result, types, multiple, PickerMode.PICK_IMAGES)) {
                        launchPrimaryDocumentPicker(prompt, result, types, multiple);
                    }
                }));
            }
        }

        if (canResolve(buildOpenDocumentIntent(types, multiple))) {
            options.add(new SourceOption("Browse files (System Files)", () -> {
                handedOff[0] = true;
                launchPrimaryDocumentPicker(prompt, result, types, multiple);
            }));
        }

        if (canResolve(buildGetContentIntent(types, multiple))) {
            options.add(new SourceOption("Other file apps", () -> {
                handedOff[0] = true;
                if (!launchPickerMode(prompt, result, types, multiple, PickerMode.GET_CONTENT)) {
                    launchPrimaryDocumentPicker(prompt, result, types, multiple);
                }
            }));
        }

        if (UploadFiles.allowsImage(types)
                && canResolve(new Intent(MediaStore.ACTION_IMAGE_CAPTURE))) {
            options.add(new SourceOption("Take photo", () -> {
                handedOff[0] = true;
                startCaptureFromMenu(prompt, result, types, multiple, UploadFiles.CAPTURE_IMAGE);
            }));
        }

        if (UploadFiles.allowsVideo(types)
                && canResolve(new Intent(MediaStore.ACTION_VIDEO_CAPTURE))) {
            options.add(new SourceOption("Record video", () -> {
                handedOff[0] = true;
                startCaptureFromMenu(prompt, result, types, multiple, UploadFiles.CAPTURE_VIDEO);
            }));
        }

        if (UploadFiles.allowsAudio(types)
                && canResolve(new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))) {
            options.add(new SourceOption("Record audio", () -> {
                handedOff[0] = true;
                startCaptureFromMenu(prompt, result, types, multiple, UploadFiles.CAPTURE_AUDIO);
            }));
        }

        // If only a single non-capture option exists, launch directly without an extra dialog.
        if (options.size() <= 1) return false;

        final Dialog dialog = createGlassDialog();
        final LinearLayout root = dialogContainer();
        final String heading = (prompt.title != null && !prompt.title.trim().isEmpty())
                ? prompt.title.trim() : "Attach file";
        root.addView(dialogTitleView(heading));

        for (int i = 0; i < options.size(); i++) {
            final SourceOption opt = options.get(i);
            final TextView btn = chipButton(opt.label, i == 0 ? dp(4) : dp(8));
            btn.setOnClickListener(v -> {
                opt.action.run();
                dialog.dismiss();
            });
            root.addView(btn);
        }

        final TextView cancel = actionButton("Cancel", dp(14));
        cancel.setOnClickListener(v -> dialog.dismiss());
        root.addView(cancel);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            activeDialogs.remove(dialog);
            if (!handedOff[0]) {
                completeDismissed(prompt, result);
            }
        });
        dialog.setOnCancelListener(d -> {
            if (!handedOff[0]) {
                completeDismissed(prompt, result);
            }
        });
        showTrackedDialog(dialog, 0.88f);
        return true;
    }

    private void startCaptureFromMenu(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                      String[] types, boolean multiple, int category) {
        final String perm = permissionForCapture(category);
        if (perm != null && !hasPermission(perm)) {
            pendingPermissionRequest = new PendingPermissionRequest(
                    prompt, result, types, multiple, true, category);
            activity.requestPermissions(new String[]{perm}, REQ_CAPTURE_PERM);
            return;
        }
        if (!launchDirectCapture(prompt, result, types, multiple, category)) {
            toast("Capture app unavailable \u2014 choosing an existing file instead");
            launchPrimaryDocumentPicker(prompt, result, types, multiple);
        }
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
        final String[] types = pendingTypes;
        final boolean multiple = pendingMultiple;
        final PickerMode pickerMode = pendingPickerMode;
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

        final int intentFlags = data != null ? data.getFlags() : 0;
        final boolean takePersistable =
                (intentFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        final ContentResolver activityCr = activity.getContentResolver();
        final ContentResolver appCr = appContext.getContentResolver();
        final List<OpenedSource> openedSources = new ArrayList<>(picked.size());
        boolean big = false;

        // Acquire source streams synchronously inside onActivityResult while transient Intent URI
        // read grants are active, then copy bytes off the UI thread to cache/uploads/.
        for (Uri u : picked) {
            Log.i(TAG, "picked URI mode=" + pickerMode + " scheme=" + u.getScheme()
                    + " authority=" + u.getAuthority()
                    + " flags=0x" + Integer.toHexString(intentFlags));

            if (usedImgCapture && u.equals(imgUri) && imgFile != null && imgFile.canRead()) {
                if (imgFile.length() >= BIG_FILE_BYTES) big = true;
                openedSources.add(new OpenedSource(
                        u, Uri.fromFile(imgFile), null, null, null, null, null, null));
                continue;
            }
            if (usedVidCapture && u.equals(vidUri) && vidFile != null && vidFile.canRead()) {
                if (vidFile.length() >= BIG_FILE_BYTES) big = true;
                openedSources.add(new OpenedSource(
                        u, Uri.fromFile(vidFile), null, null, null, null, null, null));
                continue;
            }

            if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(u.getScheme())) {
                final String path = u.getPath();
                final File f = path != null ? new File(path) : null;
                if (f != null && f.isFile() && f.length() >= BIG_FILE_BYTES) big = true;
                openedSources.add(new OpenedSource(u, null, null, null, null, null, null, null));
            } else {
                if (takePersistable && ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(u.getScheme())) {
                    try {
                        activityCr.takePersistableUriPermission(
                                u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (RuntimeException ignored) {
                    }
                }
                UriMeta meta = queryMeta(activityCr, u);
                if (meta.name == null && meta.size < 0 && activityCr != appCr) {
                    meta = queryMeta(appCr, u);
                }
                if (meta.size >= BIG_FILE_BYTES) big = true;

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

                final StreamError streamErr = new StreamError();
                ResolvedStream stream = openBestStreamInternal(activityCr, u, streamErr);
                if (stream == null && activityCr != appCr) {
                    stream = openBestStreamInternal(appCr, u, streamErr);
                }
                openedSources.add(new OpenedSource(
                        u, null, stream, meta.name, mime,
                        streamErr.stage, streamErr.summary, streamErr.cause));
            }
        }
        if (big) toast("Preparing file\u2026");

        worker.execute(() -> {
            final List<Uri> localUris = new ArrayList<>(openedSources.size());
            int failed = 0;
            AttachmentDiagnostic lastDiag = null;

            for (OpenedSource src : openedSources) {
                if (src.readyLocalUri != null) {
                    localUris.add(src.readyLocalUri);
                } else if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(src.source.getScheme())) {
                    final StreamError localErr = new StreamError();
                    final Uri copy = copyLocalFileToCache(
                            appContext, activityCr, appCr, src.source, localErr);
                    if (copy != null) {
                        localUris.add(copy);
                    } else {
                        failed++;
                        lastDiag = new AttachmentDiagnostic(
                                localErr.stage != null ? localErr.stage : "copyLocalFile",
                                pickerMode, src.source, intentFlags,
                                localErr.summary != null ? localErr.summary : "unreadable local file",
                                localErr.cause);
                    }
                } else if (src.stream == null) {
                    failed++;
                    lastDiag = new AttachmentDiagnostic(
                            src.openStage != null ? src.openStage : "openBestStream",
                            pickerMode, src.source, intentFlags,
                            src.openError != null ? src.openError : "could not open file",
                            src.openCause);
                } else {
                    final StreamError writeErr = new StreamError();
                    final Uri copy = writeStreamToCache(
                            appContext, src.stream, src.name, src.mime, writeErr);
                    if (copy != null) {
                        localUris.add(copy);
                    } else {
                        failed++;
                        lastDiag = new AttachmentDiagnostic(
                                writeErr.stage != null ? writeErr.stage : "writeStreamToCache",
                                pickerMode, src.source, intentFlags,
                                writeErr.summary != null ? writeErr.summary : "could not copy file",
                                writeErr.cause);
                    }
                }
            }

            final int failedCount = failed;
            final AttachmentDiagnostic diag = lastDiag;
            activity.runOnUiThread(() -> deliverOrQueue(
                    prompt, result, types, multiple, pickerMode, intentFlags,
                    localUris, failedCount, diag));
        });
        return true;
    }

    private void deliverOrQueue(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                String[] types, boolean multiple, PickerMode pickerMode,
                                int intentFlags, List<Uri> localUris, int failed,
                                AttachmentDiagnostic diagnostic) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            completeDismissed(prompt, result);
            return;
        }
        if (prompt.isComplete()) {
            return;
        }
        if (!activityResumed) {
            pendingDelivery = new PendingDelivery(
                    prompt, result, types, multiple, pickerMode,
                    intentFlags, localUris, failed, diagnostic);
            return;
        }
        deliver(prompt, result, types, multiple, pickerMode, intentFlags,
                localUris, failed, diagnostic);
    }

    private void deliver(FilePrompt prompt, GeckoResult<PromptResponse> result,
                         String[] types, boolean multiple, PickerMode pickerMode,
                         int intentFlags, List<Uri> localUris, int failed,
                         AttachmentDiagnostic diagnostic) {
        if (prompt.isComplete()) return;
        if (localUris.isEmpty()) {
            final AttachmentDiagnostic report = diagnostic != null
                    ? diagnostic
                    : new AttachmentDiagnostic("attach", pickerMode, null, intentFlags,
                    "No readable file returned", null);
            showErrorAndFallbackDialog(prompt, result, types, multiple, report);
            return;
        }

        final Context appContext = activity.getApplicationContext();
        try {
            confirmPromptWithUris(prompt, result, appContext, localUris);
            if (failed > 0) {
                toast(failed == 1 ? "1 file couldn't be attached"
                        : failed + " files couldn't be attached");
            }
        } catch (RuntimeException primaryError) {
            Log.w(TAG, "FilePrompt.confirm with file:// failed, trying UploadFileProvider", primaryError);
            final List<Uri> providerUris = toStagedContentUris(appContext, localUris);
            if (!providerUris.isEmpty()) {
                try {
                    confirmPromptWithUris(prompt, result, appContext, providerUris);
                    if (failed > 0) {
                        toast(failed == 1 ? "1 file couldn't be attached"
                                : failed + " files couldn't be attached");
                    }
                    return;
                } catch (RuntimeException fallbackError) {
                    Log.w(TAG, "FilePrompt.confirm fallback failed", fallbackError);
                    final AttachmentDiagnostic report = new AttachmentDiagnostic(
                            "FilePrompt.confirm (file:// + content:// fallback)",
                            pickerMode, localUris.get(0), intentFlags,
                            fallbackError.getClass().getSimpleName(), fallbackError);
                    showErrorAndFallbackDialog(prompt, result, types, multiple, report);
                    return;
                }
            }
            final AttachmentDiagnostic report = new AttachmentDiagnostic(
                    "FilePrompt.confirm", pickerMode, localUris.get(0), intentFlags,
                    primaryError.getClass().getSimpleName(), primaryError);
            showErrorAndFallbackDialog(prompt, result, types, multiple, report);
        }
    }

    private static void confirmPromptWithUris(FilePrompt prompt, GeckoResult<PromptResponse> result,
                                              Context appContext, List<Uri> uris) {
        Log.i(TAG, "file picker confirming " + uris.size() + " URI(s)");
        if (prompt.type == FilePrompt.Type.MULTIPLE) {
            result.complete(prompt.confirm(appContext, uris.toArray(new Uri[0])));
        } else {
            result.complete(prompt.confirm(appContext, uris.get(0)));
        }
    }

    private static List<Uri> toStagedContentUris(Context appContext, List<Uri> fileUris) {
        final List<Uri> out = new ArrayList<>(fileUris.size());
        final String pkg = appContext.getPackageName();
        for (Uri u : fileUris) {
            if (!ContentResolver.SCHEME_FILE.equalsIgnoreCase(u.getScheme()) || u.getPath() == null) {
                continue;
            }
            try {
                final File f = new File(u.getPath());
                final Uri contentUri = UploadFileProvider.uriForStagedFile(appContext, f);
                appContext.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                out.add(contentUri);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "could not expose staged file via UploadFileProvider", e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Interactive Error & Fallback Recovery Dialog
    // ------------------------------------------------------------------

    private void showErrorAndFallbackDialog(final FilePrompt prompt,
                                            final GeckoResult<PromptResponse> result,
                                            final String[] types,
                                            final boolean multiple,
                                            final AttachmentDiagnostic diagnostic) {
        if (activity.isFinishing() || activity.isDestroyed() || prompt.isComplete()) {
            completeDismissed(prompt, result);
            return;
        }

        final String reportText = diagnostic.formatReport(activity.getApplicationContext());
        final Dialog dialog = createGlassDialog();
        final LinearLayout root = dialogContainer();
        final boolean[] handedOff = {false};

        root.addView(dialogTitleView("Couldn't attach file"));

        final TextView subtitle = new TextView(activity);
        subtitle.setText(diagnostic.isSecurityError()
                ? "Android denied permission to read the selected file from that picker. "
                + "Try a fallback picker below or copy the details."
                : "The selected file couldn't be attached ("
                + (diagnostic.summary != null ? diagnostic.summary : "error")
                + "). Try a fallback picker below or copy the details.");
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setTextSize(13f);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        final List<SourceOption> fallbacks = buildFallbackOptions(
                prompt, result, types, multiple, diagnostic.pickerMode, dialog, handedOff);
        for (int i = 0; i < fallbacks.size(); i++) {
            final SourceOption opt = fallbacks.get(i);
            final TextView btn = chipButton(opt.label, i == 0 ? dp(2) : dp(8));
            btn.setOnClickListener(v -> {
                opt.action.run();
                dialog.dismiss();
            });
            root.addView(btn);
        }

        final TextView detailsLabel = new TextView(activity);
        detailsLabel.setText("Diagnostic details");
        detailsLabel.setTextColor(0x99FFFFFF);
        detailsLabel.setTextSize(12f);
        detailsLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(detailsLabel);

        final TextView detailsText = new TextView(activity);
        detailsText.setText(reportText);
        detailsText.setTextIsSelectable(true);
        detailsText.setTypeface(Typeface.MONOSPACE);
        detailsText.setTextSize(10f);
        detailsText.setTextColor(0xDDFFFFFF);
        final int boxPad = dp(10);
        detailsText.setPadding(boxPad, boxPad, boxPad, boxPad);

        final ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(0x44000000);
        scroll.addView(detailsText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        root.addView(scroll, slp);

        final LinearLayout bottomRow = new LinearLayout(activity);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);

        final TextView cancel = new TextView(activity);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(14f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackgroundResource(R.drawable.bg_glass_button);
        cancel.setClickable(true);
        cancel.setFocusable(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        clp.setMarginEnd(dp(6));
        bottomRow.addView(cancel, clp);

        final TextView copy = new TextView(activity);
        copy.setText("Copy details");
        copy.setTextColor(0xFFFFFFFF);
        copy.setTextSize(14f);
        copy.setGravity(Gravity.CENTER);
        copy.setBackgroundResource(R.drawable.bg_glass_button);
        copy.setClickable(true);
        copy.setFocusable(true);
        copy.setOnClickListener(v -> {
            final ClipboardManager cm = (ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("attachment_error", reportText));
            }
            toast("Error details copied");
        });
        final LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        colp.setMarginStart(dp(6));
        bottomRow.addView(copy, colp);

        root.addView(bottomRow, blp);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            activeDialogs.remove(dialog);
            if (!handedOff[0]) {
                completeDismissed(prompt, result);
            }
        });
        dialog.setOnCancelListener(d -> {
            if (!handedOff[0]) {
                completeDismissed(prompt, result);
            }
        });
        showTrackedDialog(dialog, 0.92f);
    }

    private List<SourceOption> buildFallbackOptions(final FilePrompt prompt,
                                                    final GeckoResult<PromptResponse> result,
                                                    final String[] types,
                                                    final boolean multiple,
                                                    final PickerMode failedMode,
                                                    final Dialog dialog,
                                                    final boolean[] handedOff) {
        final List<SourceOption> out = new ArrayList<>();

        if (canResolve(buildOpenDocumentIntent(types, multiple))) {
            final String label = failedMode == PickerMode.OPEN_DOCUMENT
                    ? "Retry System Files (SAF)" : "Try System Files (SAF)";
            out.add(new SourceOption(label, () -> {
                handedOff[0] = true;
                if (!launchPickerMode(prompt, result, types, multiple, PickerMode.OPEN_DOCUMENT)) {
                    toast("System Files app unavailable");
                    completeDismissed(prompt, result);
                }
            }));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && (UploadFiles.allowsImage(types) || UploadFiles.allowsVideo(types))
                && canResolve(buildPhotoPickerIntent(types, multiple))) {
            final String label = failedMode == PickerMode.PICK_IMAGES
                    ? "Retry Photo Picker" : "Try Photo Picker";
            out.add(new SourceOption(label, () -> {
                handedOff[0] = true;
                if (!launchPickerMode(prompt, result, types, multiple, PickerMode.PICK_IMAGES)) {
                    toast("Photo Picker unavailable");
                    completeDismissed(prompt, result);
                }
            }));
        }

        if (failedMode != PickerMode.GET_CONTENT && canResolve(buildGetContentIntent(types, multiple))) {
            out.add(new SourceOption("Try File Chooser (Direct)", () -> {
                handedOff[0] = true;
                if (!launchPickerMode(prompt, result, types, multiple, PickerMode.GET_CONTENT)) {
                    toast("No file chooser available");
                    completeDismissed(prompt, result);
                }
            }));
        } else if (failedMode != PickerMode.SYSTEM_CHOOSER) {
            out.add(new SourceOption("Try System Chooser", () -> {
                handedOff[0] = true;
                if (!launchPickerMode(prompt, result, types, multiple, PickerMode.SYSTEM_CHOOSER)) {
                    toast("No file chooser available");
                    completeDismissed(prompt, result);
                }
            }));
        }

        return out;
    }

    // ------------------------------------------------------------------
    // Glass dialog helpers
    // ------------------------------------------------------------------

    private Dialog createGlassDialog() {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    private void showTrackedDialog(Dialog dialog, float widthFraction) {
        final Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            DialogFit.fitAndFollow(activity, dialog, widthFraction, 0f, null);
        }
        activeDialogs.add(dialog);
        dialog.show();
    }

    private LinearLayout dialogContainer() {
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_glass_dialog);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    private TextView dialogTitleView(String title) {
        final TextView tv = new TextView(activity);
        tv.setText(title);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(17f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView chipButton(String label, int topMarginPx) {
        final TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setClickable(true);
        tv.setFocusable(true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = topMarginPx;
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView actionButton(String label, int topMarginPx) {
        final TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_glass_button);
        tv.setClickable(true);
        tv.setFocusable(true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = topMarginPx;
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
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
        pendingTypes = null;
        pendingMultiple = false;
        pendingPickerMode = null;
        pendingImageFile = null;
        pendingImageUri = null;
        pendingVideoFile = null;
        pendingVideoUri = null;
    }

    void dismissPending() {
        for (Dialog d : new ArrayList<>(activeDialogs)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {
            }
        }
        activeDialogs.clear();

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

    private static Uri copyLocalFileToCache(Context context, ContentResolver activityCr,
                                            ContentResolver appCr, Uri source,
                                            StreamError errOut) {
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
            ResolvedStream stream = openBestStreamInternal(activityCr, source, errOut);
            if (stream == null && activityCr != appCr) {
                stream = openBestStreamInternal(appCr, source, errOut);
            }
            if (stream != null) {
                return writeStreamToCache(context, stream, existing.getName(), null, errOut);
            }
            return null;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not copy local file: " + source, e);
            if (errOut != null) errOut.record("copyLocalFileToCache", e);
            if (dir != null) deleteRecursively(dir);
            return null;
        }
    }

    private static Uri writeStreamToCache(Context context, ResolvedStream resolved,
                                          String rawName, String fallbackMime,
                                          StreamError errOut) {
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
                if (errOut != null) {
                    errOut.stage = "writeStreamToCache";
                    errOut.summary = "cache dir unavailable";
                }
                return null;
            }
            final File out = new File(dir, name);
            try (OutputStream os = new FileOutputStream(out)) {
                copyStream(stream.inputStream, os);
            }
            return Uri.fromFile(out);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not write stream to cache", e);
            if (errOut != null) errOut.record("writeStreamToCache", e);
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
        final StreamError err = new StreamError();
        final ResolvedStream resolved = openBestStreamInternal(cr, source, err);
        if (resolved == null && errOut != null && err.summary != null) {
            errOut[0] = err.summary;
        }
        return resolved;
    }

    private static ResolvedStream openBestStreamInternal(ContentResolver cr, Uri source,
                                                         StreamError errOut) {
        try {
            final InputStream direct = cr.openInputStream(source);
            if (direct != null) return new ResolvedStream(direct, null, null);
        } catch (Throwable e) {
            if (errOut != null) errOut.record("ContentResolver.openInputStream", e);
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
                    if (errOut != null) errOut.record("ContentResolver.openTypedAssetFileDescriptor", e);
                    Log.w(TAG, "openTypedAssetFileDescriptor failed (" + targetMime + "): " + source, e);
                }
            }
        } catch (Throwable e) {
            if (errOut != null) errOut.record("ContentResolver.getStreamTypes", e);
            Log.w(TAG, "getStreamTypes failed: " + source, e);
        }

        try {
            final ParcelFileDescriptor pfd = cr.openFileDescriptor(source, "r");
            if (pfd != null) {
                return new ResolvedStream(new ParcelFileDescriptor.AutoCloseInputStream(pfd), null, null);
            }
        } catch (Throwable e) {
            if (errOut != null) errOut.record("ContentResolver.openFileDescriptor", e);
            Log.w(TAG, "openFileDescriptor failed: " + source, e);
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
