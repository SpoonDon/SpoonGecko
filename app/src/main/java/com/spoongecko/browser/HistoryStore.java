package com.spoongecko.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class HistoryStore extends SQLiteOpenHelper {
    private static final String TAG = "SpoonGecko";
    private static final String DB_NAME = "spoongecko_history.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "history";

    /**
     * [FIX] recordVisit() used to do its SQLite write on the UI thread (it is
     * called from onFirstContentfulPaint for every page). Writes now run on
     * this single background thread.
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spoongecko-history");
        t.setDaemon(true);
        return t;
    });

    /**
     * [PERF] Address-bar suggestions run a LIKE '%q%' scan; that now happens
     * on this thread instead of the UI thread. Kept separate from {@link #io}
     * so a suggestion never waits behind queued writes.
     */
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spoongecko-history-read");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class Entry {
        public final String url;
        public final String title;
        public Entry(String url, String title) { this.url = url; this.title = title; }
    }

    /** Receives asynchronous query results on the UI thread. */
    public interface SuggestCallback {
        void onResult(List<Entry> results);
    }

    public HistoryStore(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    /**
     * [PERF] Write-ahead logging: reads no longer block behind the write that
     * runs for every visited page, and each write needs fewer fsyncs. The
     * data itself is unchanged and remains in the same database file.
     */
    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "url TEXT PRIMARY KEY, " +
                "title TEXT, " +
                "visit_count INTEGER DEFAULT 1, " +
                "last_visited INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_last ON "
                + TABLE + "(last_visited DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_count ON "
                + TABLE + "(visit_count DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 -> v2: add performance indexes. Never drop user data.
        if (oldV < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_last ON "
                    + TABLE + "(last_visited DESC)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_count ON "
                    + TABLE + "(visit_count DESC)");
        }
    }

    public void recordVisit(String url, String title) {
        if (url == null) return;
        if (url.startsWith("about:") || url.startsWith("file:") || url.startsWith("data:")) return;
        final String safeTitle = title == null ? "" : title;
        final ContentValues cv = new ContentValues();
        cv.put("url", url);
        cv.put("title", safeTitle);
        cv.put("last_visited", System.currentTimeMillis());
        io.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                long id = db.insertWithOnConflict(TABLE, null, cv,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (id == -1) {
                    db.execSQL("UPDATE " + TABLE + " SET visit_count = visit_count + 1, " +
                                    "last_visited = ?, title = ? WHERE url = ?",
                            new Object[]{System.currentTimeMillis(), safeTitle, url});
                }
            } catch (Exception e) {
                Log.w(TAG, "recordVisit failed", e);
            }
        });
    }

    public List<Entry> suggest(String query, int limit) {
        List<Entry> out = new ArrayList<>();
        if (query == null || query.isEmpty()) return out;
        String like = "%" + query + "%";
        // [L] Cursor closed in finally: an exception mid-iteration used to leak it.
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT url, title FROM " + TABLE +
                            " WHERE url LIKE ? OR title LIKE ?" +
                            " ORDER BY visit_count DESC, last_visited DESC LIMIT ?",
                    new String[]{like, like, String.valueOf(limit)});
            while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1)));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * [PERF] Same query as {@link #suggest}, executed off the UI thread; the
     * callback is delivered on the UI thread. If the query fails or the store
     * is already closed the callback is simply not invoked.
     */
    public void suggestAsync(final String query, final int limit,
                             final SuggestCallback callback) {
        try {
            reader.execute(() -> {
                final List<Entry> results;
                try {
                    results = suggest(query, limit);
                } catch (Exception e) {
                    Log.w(TAG, "suggest failed", e);
                    return;
                }
                mainHandler.post(() -> callback.onResult(results));
            });
        } catch (RejectedExecutionException ignored) {
            // Store already closed (activity is going away) — nothing to show.
        }
    }

    public List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>();
        // [L] Same finally-close fix.
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT url, title FROM " + TABLE +
                            " ORDER BY last_visited DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1)));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public void clear() { getWritableDatabase().delete(TABLE, null, null); }

    /**
     * [FIX] Drains pending writes before closing the database.
     *
     * [PERF] This method used to be declared synchronized. The queued writes
     * call getWritableDatabase(), which needs the same monitor, so waiting for
     * them here while holding it always ran into the full 2 s timeout whenever
     * a write was still pending (e.g. exiting right after a page loaded) —
     * blocking the UI thread in onDestroy(). The wait now happens without the
     * lock; super.close() is itself synchronized.
     */
    @Override
    public void close() {
        reader.shutdown();
        io.shutdown();
        try {
            io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        super.close();
    }
}
