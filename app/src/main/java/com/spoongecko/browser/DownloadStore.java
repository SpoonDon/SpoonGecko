package com.spoongecko.browser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atomic JSON persistence for download history and partial transfer recovery.
 */
final class DownloadStore {

    private static final String TAG = "SpoonGecko";
    private static final String STORE_FILE = "downloads_history.json";
    private static final String PARTS_DIR = "download_parts";
    private static final int MAX_ENTRIES = 250;

    static final class Loaded {
        final long nextId;
        final List<DownloadEntry> entries;

        Loaded(long nextId, List<DownloadEntry> entries) {
            this.nextId = nextId;
            this.entries = entries;
        }
    }

    private final File storeFile;
    private final File tmpFile;
    private final File partsDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-dl-store");
        t.setDaemon(true);
        return t;
    });

    DownloadStore(Context context) {
        final Context app = context.getApplicationContext();
        this.storeFile = new File(app.getFilesDir(), STORE_FILE);
        this.tmpFile = new File(app.getFilesDir(), STORE_FILE + ".tmp");
        this.partsDir = new File(app.getFilesDir(), PARTS_DIR);
    }

    File partFileFor(long id) {
        if (!partsDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            partsDir.mkdirs();
        }
        return new File(partsDir, id + ".part");
    }

    void deletePartFile(long id) {
        final File part = new File(partsDir, id + ".part");
        if (part.exists()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    /** Loads persisted downloads synchronously at engine startup and recovers interrupted runs. */
    Loaded load() {
        long maxSeenId = 0;
        long storedNextId = 1;
        final List<DownloadEntry> out = new ArrayList<>();
        if (!storeFile.exists()) {
            return new Loaded(1L, out);
        }
        try (FileInputStream in = new FileInputStream(storeFile)) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            final JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            storedNextId = Math.max(1L, root.optLong("nextId", 1L));
            final JSONArray arr = root.optJSONArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    final DownloadEntry entry = DownloadEntry.fromJson(arr.optJSONObject(i));
                    if (entry == null) continue;
                    if (entry.id > maxSeenId) maxSeenId = entry.id;
                    if (entry.status.isActive()) {
                        // Previous run died while downloading/saving: convert to PAUSED if resumable
                        // partial file exists, otherwise mark FAILED so user can retry.
                        final File part = new File(partsDir, entry.id + ".part");
                        if (entry.resumable && part.exists() && part.length() > 0) {
                            entry.bytesDone = part.length();
                            entry.status = DownloadEntry.Status.PAUSED;
                            entry.errorMessage = "Paused when app closed";
                        } else {
                            entry.status = DownloadEntry.Status.FAILED;
                            entry.errorMessage = "Interrupted when app closed";
                        }
                    } else if (entry.status == DownloadEntry.Status.PAUSED) {
                        final File part = new File(partsDir, entry.id + ".part");
                        if (part.exists()) {
                            entry.bytesDone = part.length();
                        }
                    }
                    out.add(entry);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loading downloads history failed", e);
        }
        return new Loaded(Math.max(storedNextId, maxSeenId + 1), out);
    }

    /** Persists a snapshot asynchronously in strict order, keeping active/paused and newest entries. */
    void saveAsync(final long nextId, final List<DownloadEntry> snapshot) {
        final int n = snapshot.size();
        final List<DownloadEntry> copy = new ArrayList<>(Math.min(n, MAX_ENTRIES));
        if (n <= MAX_ENTRIES) {
            for (int i = 0; i < n; i++) {
                copy.add(snapshot.get(i).copy());
            }
        } else {
            final boolean[] keep = new boolean[n];
            int remaining = MAX_ENTRIES;
            for (int i = 0; i < n && remaining > 0; i++) {
                final DownloadEntry e = snapshot.get(i);
                if (e.status.isActive() || e.status == DownloadEntry.Status.PAUSED) {
                    keep[i] = true;
                    remaining--;
                }
            }
            for (int i = n - 1; i >= 0 && remaining > 0; i--) {
                if (!keep[i]) {
                    keep[i] = true;
                    remaining--;
                }
            }
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    copy.add(snapshot.get(i).copy());
                }
            }
        }
        io.execute(() -> writeSync(nextId, copy));
    }

    private void writeSync(long nextId, List<DownloadEntry> entries) {
        try {
            final JSONArray arr = new JSONArray();
            for (DownloadEntry e : entries) {
                arr.put(e.toJson());
            }
            final JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("nextId", nextId);
            root.put("entries", arr);
            final byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(data);
                fos.flush();
            }
            if (!tmpFile.renameTo(storeFile)) {
                try (FileOutputStream fos = new FileOutputStream(storeFile)) {
                    fos.write(data);
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            Log.w(TAG, "saving downloads history failed", e);
        }
    }
}
