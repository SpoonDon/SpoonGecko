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
import java.util.Collections;
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
     * [SUGGEST] How many rows the database hands to the ranking step. The query only filters
     * (every word must appear in the address or the title); the final order is decided in
     * SuggestionRanker, so this has to be larger than the number of suggestions shown.
     */
    private static final int SUGGEST_CANDIDATES = 400;

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
        /** When the page was last visited (milliseconds since 1970); 0 = unknown (old data). */
        public final long lastVisited;
        /** How many times the page was visited; 0 = unknown. */
        public final int visitCount;

        public Entry(String url, String title) { this(url, title, 0L, 0); }

        public Entry(String url, String title, long lastVisited, int visitCount) {
            this.url = url;
            this.title = title;
            this.lastVisited = lastVisited;
            this.visitCount = visitCount;
        }
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

    /**
     * [SUGGEST] Address-bar suggestions for what was typed.
     *
     * The database only filters: every word of the query must appear in the address or in the
     * title (wildcard characters in the text are matched literally). The order, and the removal
     * of the same page listed under several addresses, is done by {@link SuggestionRanker}.
     */
    public List<Entry> suggest(String query, int limit) {
        final SuggestionRanker.Query q = SuggestionRanker.parse(query);
        if (q == null || limit <= 0) return new ArrayList<>();

        final StringBuilder where = new StringBuilder();
        final List<String> args = new ArrayList<>();
        for (String token : q.tokens) {
            if (where.length() > 0) where.append(" AND ");
            where.append("(url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\')");
            final String like = "%" + SuggestionRanker.escapeLike(token) + "%";
            args.add(like);
            args.add(like);
        }
        args.add(String.valueOf(SUGGEST_CANDIDATES));

        final List<Entry> candidates = new ArrayList<>();
        // [L] Cursor closed in finally: an exception mid-iteration used to leak it.
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT url, title, visit_count, last_visited FROM " + TABLE +
                            " WHERE " + where +
                            " ORDER BY visit_count DESC, last_visited DESC LIMIT ?",
                    args.toArray(new String[0]));
            while (c.moveToNext()) {
                candidates.add(new Entry(c.getString(0), c.getString(1), c.getLong(3), c.getInt(2)));
            }
        } finally {
            if (c != null) c.close();
        }
        return SuggestionRanker.rank(candidates, q, limit, System.currentTimeMillis());
    }

    /**
     * [PERF] Same query as {@link #suggest}, executed off the UI thread; the
     * callback is delivered on the UI thread. If the store is already closed
     * the callback is simply not invoked.
     *
     * [SUGGEST] If the query itself fails, the callback now receives an EMPTY list. It used to
     * be skipped, which left the previous (now wrong) suggestions on screen while typing went on.
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
                    mainHandler.post(() -> callback.onResult(Collections.<Entry>emptyList()));
                    return;
                }
                mainHandler.post(() -> callback.onResult(results));
            });
        } catch (RejectedExecutionException ignored) {
            // Store already closed (activity is going away) — nothing to show.
        }
    }

    /** The most recently visited pages, newest first. */
    public List<Entry> recent(int limit) {
        return loadEntries("last_visited DESC", limit);
    }

    /** [HISTORY] The least recently visited pages, oldest first. */
    public List<Entry> oldest(int limit) {
        return loadEntries("last_visited ASC", limit);
    }

    private List<Entry> loadEntries(String orderBy, int limit) {
        List<Entry> out = new ArrayList<>();
        // [L] Same finally-close fix.
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT url, title, visit_count, last_visited FROM " + TABLE +
                            " ORDER BY " + orderBy + " LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (c.moveToNext()) {
                out.add(new Entry(c.getString(0), c.getString(1), c.getLong(3), c.getInt(2)));
            }
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
