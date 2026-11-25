package com.spoongecko.browser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoPreferenceController;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [UPLOAD] Attaching local files to a web page ({@code <input type=file>}).
 *
 * Why this exists: the picker used to give GeckoView the {@code content://} address that Android's
 * file picker returns. GeckoView can only turn a {@code file://} address into a file, so for a
 * {@code content://} one it logged "Only file URIs are supported" and told the page that no file
 * was chosen: nothing could be attached. (A known GeckoView limitation; Firefox for Android works
 * around it the same way.) The picked files are now copied into the app's cache and GeckoView is
 * given those copies.
 *
 * Also fixed here:
 *  - the picker was given file EXTENSIONS (".pdf") as if they were MIME types, which made files
 *    unselectable on pages that say accept=".pdf" (see UploadFiles.acceptedTypes);
 *  - copying happens off the UI thread (a large video used to freeze the app while it copied);
 *  - a picker that never reported back used to block every later attach until restart: a new
 *    request now replaces it;
 *  - failures are shown to the user instead of silently attaching nothing;
 *  - the engine's own "no file picker from an unfocused page" rule is switched off (see
 *    relaxPickerGate): with that rule on, the engine cancels the request BEFORE this app is ever
 *    asked, so no picker opens at all;
 *  - if Android closed the browser while the picker was open, the chosen file can no longer be
 *    attached to the old page: the user is told to attach it again instead of nothing happening.
 *
 * No storage permission is needed: the system picker grants access to exactly the files the user
 * chooses.
 */
final class FilePicker {

    private static final String TAG = "SpoonGecko";

    /**
     * Engine setting (Firefox 136+): a page may only open a file picker while the engine thinks
     * the browser window has focus. An embedded GeckoView often does not have that focus, so the
     * engine answers "cancelled" to the page without calling the app. Set to false = always allow.
     * (The same value is also put in the engine's start-up config by ExtensionManager; setting it
     * again here at run time is a second, independent way to get the same result.)
     */
    private static final String PICKER_GATE_PREF = "browser.disable_pickers_background_tabs";
    private static volatile boolean pickerGateRelaxed;

    /** Request codes REQ_BASE .. REQ_BASE+SLOTS-1 belong to the file picker. */
    private static final int REQ_BASE = 0x7110;
    private static final int SLOTS = 16;

    private static final String UPLOAD_DIR = "uploads";
    /** Copies older than this are deleted (a page keeps using its copy until the file is sent). */
    private static final long KEEP_COPIES_MS = 24L * 60 * 60 * 1000;
    /** From this size on the user is told the file is being prepared. */
    private static final long BIG_FILE_BYTES = 8L * 1024 * 1024;

    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-upload");
        t.setDaemon(true);
        return t;
    });

    // UI thread only.
    private FilePrompt pendingPrompt;
    private GeckoResult<PromptResponse> pendingResult;
    private int pendingSlot;
    private int nextSlot;

    FilePicker(Activity activity) {
        this.activity = activity;
        worker.execute(this::deleteOldCopies);
        relaxPickerGate();
    }

    // ------------------------------------------------------------------
    // Engine rule that can stop the picker from ever opening
    // ------------------------------------------------------------------

    /**
     * Switches the engine's "no picker from an unfocused page" rule off. Runs once per process.
     * It is posted to the main thread so it runs after the activity's onCreate() has created the
     * engine, and repeated a few seconds later in case the engine was not ready the first time.
     * Setting the same value twice is harmless. Never throws.
     */
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
            // The engine may not be running yet; the delayed second attempt (or the next activity)
            // tries again. Uploads must never crash because of this.
            Log.w(TAG, "could not switch off the engine's file-picker rule", t);
        }
    }

    // ------------------------------------------------------------------
    // The page asks for a file
    // ------------------------------------------------------------------

    GeckoResult<PromptResponse> onFilePrompt(final FilePrompt prompt) {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        Log.i(TAG, "file prompt: type=" + prompt.type
                + " accept=" + Arrays.toString(prompt.mimeTypes));
        if (activity.isFinishing() || activity.isDestroyed()) {
            result.complete(prompt.dismiss());
            return result;
        }
        if (prompt.type == FilePrompt.Type.FOLDER) {
            // Directory uploads would need ACTION_OPEN_DOCUMENT_TREE and a way to hand over a
            // whole tree; not supported. Say so instead of doing nothing.
            toast("Uploading a whole folder isn't supported");
            result.complete(prompt.dismiss());
            return result;
        }

        // A picker that never reported back (the activity was recreated, the picker app died)
        // must not block this one: the old request is cancelled and the new one takes over.
        dismissPending();
        worker.execute(this::deleteOldCopies);

        final boolean multiple = prompt.type == FilePrompt.Type.MULTIPLE;
        final String[] types = UploadFiles.acceptedTypes(prompt.mimeTypes, platformResolver());
        final int slot = nextSlot;
        nextSlot = (nextSlot + 1) % SLOTS;

        boolean started = launch(pickerIntent(Intent.ACTION_OPEN_DOCUMENT, types, multiple), slot);
        if (!started) {
            // Some devices have no Documents UI: ACTION_GET_CONTENT is the older equivalent.
            started = launch(pickerIntent(Intent.ACTION_GET_CONTENT, types, multiple), slot);
        }
        if (!started) {
            toast("No app available to choose files");
            result.complete(prompt.dismiss());
            return result;
        }
        pendingPrompt = prompt;
        pendingResult = result;
        pendingSlot = slot;
        return result;
    }

    private static Intent pickerIntent(String action, String[] types, boolean multiple) {
        final Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
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

    /** Called from MainActivity.onActivityResult(); true when the result belongs to the picker. */
    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode < REQ_BASE || requestCode >= REQ_BASE + SLOTS) return false;

        // Nothing is waiting for this answer. The usual reason: Android closed the browser while
        // the picker was open (low memory), so the page that asked for the file is gone and the
        // file can no longer be attached to it. Tell the user instead of doing nothing.
        if (pendingPrompt == null) {
            if (resultCode == Activity.RESULT_OK && data != null
                    && (data.getData() != null || data.getClipData() != null)) {
                toast("Browser restarted while choosing the file \u2014 please attach it again");
            }
            return true;
        }
        // The answer of a picker that was replaced by a newer request: nothing waits for it.
        if (requestCode != REQ_BASE + pendingSlot) return true;

        final FilePrompt prompt = pendingPrompt;
        final GeckoResult<PromptResponse> result = pendingResult;
        pendingPrompt = null;
        pendingResult = null;

        final List<Uri> picked = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getData() != null) picked.add(data.getData());
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    final Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null && !picked.contains(u)) picked.add(u);
                }
            }
        }
        Log.i(TAG, "file picker answered: resultCode=" + resultCode + " files=" + picked.size());
        if (picked.isEmpty()) {                       // cancelled
            completeDismissed(prompt, result);
            return true;
        }

        final Context appContext = activity.getApplicationContext();
        worker.execute(() -> {
            // Off the UI thread: a large file takes a while to copy.
            boolean big = false;
            for (Uri u : picked) if (sizeOf(appContext, u) >= BIG_FILE_BYTES) big = true;
            if (big) activity.runOnUiThread(() -> toast("Preparing file\u2026"));

            final List<Uri> copies = new ArrayList<>();
            int failed = 0;
            for (Uri u : picked) {
                final Uri copy = copyToCache(appContext, u);
                if (copy != null) copies.add(copy); else failed++;
            }
            final int failedCount = failed;
            activity.runOnUiThread(() -> deliver(prompt, result, copies, failedCount));
        });
        return true;
    }

    /** UI thread: hands the copies to GeckoView (confirm() must be called there). */
    private void deliver(FilePrompt prompt, GeckoResult<PromptResponse> result,
                         List<Uri> copies, int failed) {
        try {
            if (copies.isEmpty()) {
                toast("Couldn't attach the file");
                completeDismissed(prompt, result);
                return;
            }
            // A single-file input takes exactly one file.
            final List<Uri> files = prompt.type == FilePrompt.Type.MULTIPLE
                    ? copies : copies.subList(0, 1);
            result.complete(prompt.confirm(activity, files.toArray(new Uri[0])));
            Log.i(TAG, "file(s) handed to the page: " + files.size());
            if (failed > 0) {
                toast(failed == 1 ? "1 file couldn't be attached"
                        : failed + " files couldn't be attached");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "attaching the file failed", e);
            toast("Couldn't attach the file");
            completeDismissed(prompt, result);
        }
    }

    private static void completeDismissed(FilePrompt prompt, GeckoResult<PromptResponse> result) {
        try {
            result.complete(prompt.dismiss());
        } catch (RuntimeException ignored) {
            // already completed (page navigated, session closed)
        }
    }

    /** Cancels a request that is still waiting for the picker (activity going away, or replaced). */
    void dismissPending() {
        final FilePrompt prompt = pendingPrompt;
        final GeckoResult<PromptResponse> result = pendingResult;
        pendingPrompt = null;
        pendingResult = null;
        if (prompt != null && result != null) completeDismissed(prompt, result);
    }

    // ------------------------------------------------------------------
    // Copies in the cache
    // ------------------------------------------------------------------

    /**
     * Copies a picked file to cache/uploads/&lt;random&gt;/&lt;its own name&gt; and returns the
     * file:// address of the copy, or null if it could not be read. Own folder per file, so two
     * files with the same name never clash and the page still sees the real name.
     */
    private static Uri copyToCache(Context context, Uri source) {
        File dir = null;
        try {
            if ("file".equals(source.getScheme())) {
                final String path = source.getPath();
                return path != null && new File(path).canRead() ? source : null;
            }
            final ContentResolver cr = context.getContentResolver();
            String name = UploadFiles.safeFileName(displayName(cr, source));
            final String type = cr.getType(source);
            if (type != null) {
                name = UploadFiles.withExtension(name,
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(type));
            }
            dir = new File(new File(context.getCacheDir(), UPLOAD_DIR), UUID.randomUUID().toString());
            if (!dir.mkdirs() && !dir.isDirectory()) return null;
            final File out = new File(dir, name);
            try (InputStream in = cr.openInputStream(source);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) {
                    deleteRecursively(dir);
                    return null;
                }
                final byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return Uri.fromFile(out);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not copy the chosen file", e);
            if (dir != null) deleteRecursively(dir);
            return null;
        }
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

    /** Size in bytes, or -1 if the provider does not say. */
    private static long sizeOf(Context context, Uri uri) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
            // size is only used to decide whether to show a message
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

    private static void deleteRecursively(File f) {
        final File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        // best effort: a copy that cannot be deleted now is retried by the next clean-up
        if (!f.delete()) Log.w(TAG, "could not delete " + f);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
