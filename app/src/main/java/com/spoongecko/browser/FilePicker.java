package com.spoongecko.browser;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
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
import java.io.FileNotFoundException;
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
 * [UPLOAD] Attaching local files to a web page ({@code <input type=file>}).
 *
 * Why this exists: the picker used to give GeckoView the {@code content://} address that Android's
 * file picker returns. GeckoView can only turn a {@code file://} address into a file, so for a
 * {@code content://} one it logged "Only file URIs are supported" and told the page that no file
 * was chosen: nothing could be attached. (A known GeckoView limitation; Firefox for Android works
 * around it the same way.) The picked files are now copied into the app's cache and GeckoView is
 * given those copies.
 *
 * Also handled here:
 *  - the picker was given file EXTENSIONS (".pdf") as if they were MIME types, which made files
 *    unselectable on pages that say accept=".pdf" (see UploadFiles.acceptedTypes);
 *  - copying happens off the UI thread (a large video used to freeze the app while it copied);
 *  - a picker that never reported back used to block every later attach until restart: a new
 *    request now replaces it;
 *  - failures are shown to the user, with the reason, instead of silently attaching nothing;
 *  - the engine's own "no file picker from an unfocused page" rule is switched off (see
 *    relaxPickerGate): with that rule on, the engine cancels the request BEFORE this app is ever
 *    asked, so no picker opens at all;
 *  - some devices' file pickers return a file that Android then refuses to let the app read
 *    (SecurityException). Android has two system pickers (ACTION_OPEN_DOCUMENT and
 *    ACTION_GET_CONTENT); when one of them hands over a file the app may not read, the request is
 *    asked again once through the other one, and the one that works is remembered;
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

    /**
     * Which system picker is opened first. Starts with the document picker; switched to the other
     * one for the rest of the session when only that one handed over files the app could read.
     */
    private static volatile String preferredAction = Intent.ACTION_OPEN_DOCUMENT;

    /** Request codes REQ_BASE .. REQ_BASE+SLOTS-1 belong to the file picker. */
    private static final int REQ_BASE = 0x7110;
    private static final int SLOTS = 16;

    private static final String UPLOAD_DIR = "uploads";
    /** Copies older than this are deleted (a page keeps using its copy until the file is sent). */
    private static final long KEEP_COPIES_MS = 24L * 60 * 60 * 1000;
    /** From this size on the user is told the file is being prepared. */
    private static final long BIG_FILE_BYTES = 8L * 1024 * 1024;

    /** One request from a page, with what is needed to answer it or to ask the picker again. */
    private static final class Ask {
        final FilePrompt prompt;
        final GeckoResult<PromptResponse> result;
        final String[] types;
        final boolean multiple;
        /** Which system picker was opened for it. */
        final String action;
        final int slot;
        /** The other kind of picker was already used for this request: do not switch again. */
        final boolean retried;

        Ask(FilePrompt prompt, GeckoResult<PromptResponse> result, String[] types,
            boolean multiple, String action, int slot, boolean retried) {
            this.prompt = prompt;
            this.result = result;
            this.types = types;
            this.multiple = multiple;
            this.action = action;
            this.slot = slot;
            this.retried = retried;
        }
    }

    /** What went wrong while copying the picked files (filled on the worker thread). */
    private static final class CopyReport {
        /** Why the last failed copy failed, in words the user can read out. */
        String reason;
        /** Android refused the app access to a picked file. */
        boolean denied;
    }

    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-upload");
        t.setDaemon(true);
        return t;
    });

    // UI thread only: the request that is waiting for the picker.
    private Ask pending;
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

        final Ask ask = openPicker(prompt, result, types, multiple, preferredAction, false);
        if (ask == null) {
            toast("No app available to choose files");
            result.complete(prompt.dismiss());
            return result;
        }
        pending = ask;
        return result;
    }

    /**
     * Opens a system picker for the request. If that kind of picker does not exist on the device
     * (some have no Documents UI) and the other kind has not been used yet, the other one is
     * opened instead. Returns null when no picker could be started.
     */
    private Ask openPicker(FilePrompt prompt, GeckoResult<PromptResponse> result, String[] types,
                           boolean multiple, String action, boolean retried) {
        final int slot = nextSlot;
        nextSlot = (nextSlot + 1) % SLOTS;
        if (launch(pickerIntent(action, types, multiple), slot)) {
            return new Ask(prompt, result, types, multiple, action, slot, retried);
        }
        if (!retried) {
            final String other = otherAction(action);
            if (launch(pickerIntent(other, types, multiple), slot)) {
                return new Ask(prompt, result, types, multiple, other, slot, true);
            }
        }
        return null;
    }

    private static String otherAction(String action) {
        return Intent.ACTION_OPEN_DOCUMENT.equals(action)
                ? Intent.ACTION_GET_CONTENT : Intent.ACTION_OPEN_DOCUMENT;
    }

    private static String pickerName(String action) {
        return Intent.ACTION_GET_CONTENT.equals(action) ? "GET_CONTENT" : "OPEN_DOCUMENT";
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
        final Ask ask = pending;
        if (ask == null) {
            if (resultCode == Activity.RESULT_OK && data != null
                    && (data.getData() != null || data.getClipData() != null)) {
                toast("Browser restarted while choosing the file \u2014 please attach it again");
            }
            return true;
        }
        // The answer of a picker that was replaced by a newer request: nothing waits for it.
        if (requestCode != REQ_BASE + ask.slot) return true;
        pending = null;

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
        Log.i(TAG, "file picker (" + pickerName(ask.action) + ") answered: resultCode=" + resultCode
                + " files=" + picked.size()
                + (picked.isEmpty() ? "" : " first=" + picked.get(0).getScheme() + "://"
                + picked.get(0).getAuthority()));
        if (picked.isEmpty()) {                       // cancelled
            completeDismissed(ask.prompt, ask.result);
            return true;
        }

        final Context appContext = activity.getApplicationContext();
        worker.execute(() -> {
            // Off the UI thread: a large file takes a while to copy.
            boolean big = false;
            for (Uri u : picked) if (sizeOf(appContext, u) >= BIG_FILE_BYTES) big = true;
            if (big) activity.runOnUiThread(() -> toast("Preparing file\u2026"));

            final List<Uri> copies = new ArrayList<>();
            final CopyReport report = new CopyReport();
            int failed = 0;
            for (Uri u : picked) {
                final Uri copy = copyToCache(appContext, u, report);
                if (copy != null) copies.add(copy); else failed++;
            }
            final int failedCount = failed;
            activity.runOnUiThread(() -> deliver(ask, copies, failedCount, report));
        });
        return true;
    }

    /** UI thread: hands the copies to GeckoView (confirm() must be called there). */
    private void deliver(Ask ask, List<Uri> copies, int failed, CopyReport report) {
        final FilePrompt prompt = ask.prompt;
        final GeckoResult<PromptResponse> result = ask.result;
        try {
            if (copies.isEmpty()) {
                if (report.denied && !ask.retried && pending == null
                        && !activity.isFinishing() && !activity.isDestroyed()) {
                    // Android refused the app access to the file this picker handed over. The other
                    // kind of system picker often gives a file the app may read: ask once more
                    // through that one (the page keeps waiting meanwhile).
                    final Ask again = openPicker(prompt, result, ask.types, ask.multiple,
                            otherAction(ask.action), true);
                    if (again != null) {
                        pending = again;
                        Log.i(TAG, pickerName(ask.action) + " gave a file Android won't let the app "
                                + "read; asking again through " + pickerName(again.action));
                        toastLong("That file picker didn't allow access \u2014 "
                                + "please choose the file again");
                        return;
                    }
                }
                // The reason is shown on purpose: it says WHAT went wrong without needing a log.
                toastLong("Couldn't attach the file"
                        + (report.reason == null ? ""
                        : " (" + report.reason + ", via " + pickerName(ask.action) + ")"));
                completeDismissed(prompt, result);
                return;
            }
            if (prompt.isComplete()) {
                // The engine already closed this request (page reloaded or closed meanwhile).
                toastLong("Couldn't attach the file (the page stopped waiting for it "
                        + "\u2014 try again)");
                return;
            }
            // A single-file input takes exactly one file.
            final List<Uri> files = prompt.type == FilePrompt.Type.MULTIPLE
                    ? copies : copies.subList(0, 1);
            result.complete(prompt.confirm(activity, files.toArray(new Uri[0])));
            Log.i(TAG, "file(s) handed to the page: " + files.size()
                    + " via " + pickerName(ask.action));
            // The first picker did not work but this one did: use this one from now on.
            if (ask.retried) preferredAction = ask.action;
            if (failed > 0) {
                toast(failed == 1 ? "1 file couldn't be attached"
                        : failed + " files couldn't be attached");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "attaching the file failed", e);
            toastLong("Couldn't attach the file (hand-over: " + describe(e, null) + ")");
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
        final Ask ask = pending;
        pending = null;
        if (ask != null) completeDismissed(ask.prompt, ask.result);
    }

    // ------------------------------------------------------------------
    // Copies in the cache
    // ------------------------------------------------------------------

    /**
     * Copies a picked file to cache/uploads/&lt;random&gt;/&lt;its own name&gt; and returns the
     * file:// address of the copy, or null if it could not be read (then the report says why).
     * Own folder per file, so two files with the same name never clash and the page still sees
     * the real name.
     */
    private static Uri copyToCache(Context context, Uri source, CopyReport report) {
        File dir = null;
        try {
            if ("file".equals(source.getScheme())) {
                final String path = source.getPath();
                if (path != null && new File(path).canRead()) return source;
                report.reason = "file not readable [file://]";
                return null;
            }
            final ContentResolver cr = context.getContentResolver();
            String name = UploadFiles.safeFileName(displayName(cr, source));
            final String type = cr.getType(source);
            final String typeExt = type == null
                    ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
            if (type != null) name = UploadFiles.withExtension(name, typeExt);
            dir = new File(new File(context.getCacheDir(), UPLOAD_DIR), UUID.randomUUID().toString());
            if (!dir.mkdirs() && !dir.isDirectory()) {
                report.reason = "cache folder not writable";
                return null;
            }
            try (InputStream in = openStream(cr, source)) {
                if (in == null) {
                    report.reason = "no data from the file provider [" + source.getAuthority() + "]";
                    deleteRecursively(dir);
                    return null;
                }
                File out = new File(dir, name);
                OutputStream os;
                try {
                    os = new FileOutputStream(out);
                } catch (FileNotFoundException nameRejected) {
                    // The name itself was refused (too long for the file system, odd characters):
                    // keep the file type, drop the name, rather than fail the whole upload.
                    final String ext = UploadFiles.extensionOf(name);
                    out = new File(dir, UploadFiles.withExtension(UploadFiles.FALLBACK_NAME, ext));
                    os = new FileOutputStream(out);
                }
                try (OutputStream o = os) {
                    final byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                return Uri.fromFile(out);
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not copy the chosen file", e);
            report.reason = describe(e, source);
            if (e instanceof SecurityException) report.denied = true;
            if (dir != null) deleteRecursively(dir);
            return null;
        }
    }

    /**
     * Opens the picked file for reading. Most providers answer to openInputStream(); a few only to
     * openAssetFileDescriptor(), so that is tried when the first one is refused.
     */
    private static InputStream openStream(ContentResolver cr, Uri uri) throws IOException {
        try {
            final InputStream in = cr.openInputStream(uri);
            if (in != null) return in;
        } catch (FileNotFoundException | SecurityException first) {
            AssetFileDescriptor fd = null;
            try {
                fd = cr.openAssetFileDescriptor(uri, "r");
            } catch (Exception ignored) {
                // reported through `first` below
            }
            if (fd != null) return fd.createInputStream();
            throw first;
        }
        final AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r");
        return fd == null ? null : fd.createInputStream();
    }

    /** Short, human-readable reason for a failure: what happened and which file provider. */
    private static String describe(Throwable e, Uri uri) {
        final String where = uri == null ? null : uri.getScheme() + "://" + uri.getAuthority();
        // A permission error's own message is long and adds nothing to "access refused".
        if (e instanceof SecurityException) {
            return "access refused" + (where == null ? "" : " [" + where + "]");
        }
        final StringBuilder b = new StringBuilder(e.getClass().getSimpleName());
        final String m = e.getMessage();
        if (m != null && !m.isEmpty()) {
            b.append(": ").append(m.length() > 80 ? m.substring(0, 80) + "\u2026" : m);
        }
        if (where != null) b.append(" [").append(where).append(']');
        return b.toString();
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

    private void toastLong(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
