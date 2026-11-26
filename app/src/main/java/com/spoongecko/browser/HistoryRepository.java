package com.spoongecko.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.sqlite.db.SimpleSQLiteQuery;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Repository mediating access between browser navigation/UI and the Room {@link HistoryDao}.
 * All database writes are serialized on a single background thread and reads execute off the
 * main thread, delivering async results on the main thread.
 */
public final class HistoryRepository {

    private static final String TAG = "SpoonGecko";
    private static final String LEGACY_DB_NAME = "spoongecko_history.db";
    private static final String MIGRATION_PREFS = "spoongecko_history_room_prefs";
    private static final String KEY_MIGRATED_LEGACY = "migrated_legacy_v1";
    private static final int SUGGEST_CANDIDATES = 400;

    private static volatile HistoryRepository instance;

    private final Context appContext;
    private final HistoryDatabase database;
    private final HistoryDao dao;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-history");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-history-read");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HistoryRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.database = HistoryDatabase.getInstance(this.appContext);
        this.dao = this.database.historyDao();
        scheduleLegacyMigration();
    }

    public static HistoryRepository get(Context context) {
        if (instance == null) {
            synchronized (HistoryRepository.class) {
                if (instance == null) {
                    instance = new HistoryRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private void scheduleLegacyMigration() {
        io.execute(() -> {
            final SharedPreferences prefs =
                    appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_MIGRATED_LEGACY, false)) return;

            try {
                final File legacyFile = appContext.getDatabasePath(LEGACY_DB_NAME);
                if (legacyFile != null && legacyFile.exists() && dao.countAll() == 0) {
                    final List<HistoryEntity> imported = readLegacyEntries(legacyFile);
                    if (!imported.isEmpty()) {
                        database.runInTransaction(() -> dao.insertAll(imported));
                    }
                }
                prefs.edit().putBoolean(KEY_MIGRATED_LEGACY, true).apply();
            } catch (Exception e) {
                Log.w(TAG, "legacy history migration failed", e);
            }
        });
    }

    private static List<HistoryEntity> readLegacyEntries(File legacyFile) {
        final List<HistoryEntity> out = new ArrayList<>();
        SQLiteDatabase legacyDb = null;
        Cursor cursor = null;
        try {
            legacyDb = SQLiteDatabase.openDatabase(
                    legacyFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = legacyDb.rawQuery(
                    "SELECT url, title, last_visited FROM history", null);
            while (cursor.moveToNext()) {
                final String url = cursor.getString(0);
                if (!isTrackableUrl(url, null)) continue;
                final String title = cursor.isNull(1) ? "" : cursor.getString(1);
                final long lastVisited = cursor.isNull(2) ? 0L : cursor.getLong(2);
                out.add(new HistoryEntity(
                        url.trim(),
                        title == null ? "" : title,
                        lastVisited));
            }
        } catch (Exception e) {
            Log.w(TAG, "failed reading legacy history database", e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
            if (legacyDb != null) {
                try {
                    legacyDb.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    public static boolean isTrackableUrl(String url, String title) {
        if (url == null) return false;
        final String trimmed = url.trim();
        if (trimmed.isEmpty()) return false;
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("about:")
                || lower.startsWith("file:")
                || lower.startsWith("data:")
                || lower.startsWith("javascript:")) {
            return false;
        }
        if (ErrorPages.isErrorPageUri(trimmed)) return false;
        return !ErrorPages.isErrorTitle(title);
    }

    public void recordVisit(String url, String title) {
        recordVisit(url, title, System.currentTimeMillis());
    }

    public void recordVisit(final String url, final String title, final long visitedAt) {
        if (!isTrackableUrl(url, title)) return;
        final String cleanUrl = url.trim();
        final String safeTitle = title == null ? "" : title;
        final long timestamp = visitedAt > 0L ? visitedAt : System.currentTimeMillis();
        io.execute(() -> {
            try {
                dao.insert(new HistoryEntity(cleanUrl, safeTitle, timestamp));
            } catch (Exception e) {
                Log.w(TAG, "recordVisit failed", e);
            }
        });
    }

    public List<HistoryStore.Entry> suggest(String query, int limit) {
        final SuggestionRanker.Query q = SuggestionRanker.parse(query);
        if (q == null || limit <= 0) return new ArrayList<>();

        final StringBuilder where = new StringBuilder();
        final List<Object> args = new ArrayList<>();
        for (String token : q.tokens) {
            if (where.length() > 0) where.append(" AND ");
            where.append("(h.url LIKE ? ESCAPE '\\' OR h.title LIKE ? ESCAPE '\\')");
            final String like = "%" + SuggestionRanker.escapeLike(token) + "%";
            args.add(like);
            args.add(like);
        }
        args.add(SUGGEST_CANDIDATES);

        final String sql = "SELECT h.url AS url, "
                + "COALESCE((SELECT h2.title FROM history_entries h2 "
                + "WHERE h2.url = h.url ORDER BY h2.visited_at DESC, h2.id DESC LIMIT 1), '') AS title, "
                + "MAX(h.visited_at) AS lastVisited, "
                + "COUNT(*) AS visitCount "
                + "FROM history_entries h "
                + "WHERE " + where + " "
                + "GROUP BY h.url "
                + "ORDER BY visitCount DESC, lastVisited DESC "
                + "LIMIT ?";

        final List<HistoryDao.HistorySummary> summaries =
                dao.querySummaries(new SimpleSQLiteQuery(sql, args.toArray()));
        final List<HistoryStore.Entry> candidates = toEntries(summaries);
        return SuggestionRanker.rank(candidates, q, limit, System.currentTimeMillis());
    }

    public void suggestAsync(final String query, final int limit,
                             final HistoryStore.SuggestCallback callback) {
        if (callback == null) return;
        reader.execute(() -> {
            List<HistoryStore.Entry> results;
            try {
                results = suggest(query, limit);
            } catch (Exception e) {
                Log.w(TAG, "suggest failed", e);
                results = Collections.emptyList();
            }
            final List<HistoryStore.Entry> finalResults = results;
            mainHandler.post(() -> callback.onResult(finalResults));
        });
    }

    public List<HistoryStore.Entry> recent(int limit) {
        if (limit <= 0) return Collections.emptyList();
        return toEntries(dao.getRecentSummaries(limit));
    }

    public List<HistoryStore.Entry> oldest(int limit) {
        if (limit <= 0) return Collections.emptyList();
        return toEntries(dao.getOldestSummaries(limit));
    }

    public void loadRecentVisitsAsync(final int limit,
                                      final Consumer<List<HistoryEntity>> callback) {
        if (callback == null) return;
        reader.execute(() -> {
            List<HistoryEntity> visits;
            try {
                visits = limit > 0 ? dao.getRecentVisits(limit) : Collections.emptyList();
            } catch (Exception e) {
                Log.w(TAG, "loadRecentVisitsAsync failed", e);
                visits = Collections.emptyList();
            }
            final List<HistoryEntity> finalVisits = visits;
            mainHandler.post(() -> callback.accept(finalVisits));
        });
    }

    public void loadRowsAsync(final HistoryRows.Mode mode, final int limit,
                              final Set<String> removedSnapshot, final ZoneId zone,
                              final Locale locale, final Consumer<List<HistoryRows.Row>> callback) {
        if (callback == null) return;
        reader.execute(() -> {
            List<HistoryRows.Row> rows;
            try {
                List<HistoryStore.Entry> source = mode == HistoryRows.Mode.OLDEST
                        ? oldest(limit) : recent(limit);
                if (removedSnapshot != null && !removedSnapshot.isEmpty()) {
                    final List<HistoryStore.Entry> kept = new ArrayList<>(source.size());
                    for (HistoryStore.Entry e : source) {
                        if (e == null || e.url == null || !removedSnapshot.contains(e.url)) {
                            kept.add(e);
                        }
                    }
                    source = kept;
                }
                rows = HistoryRows.build(source, mode, System.currentTimeMillis(), zone, locale);
            } catch (Exception e) {
                Log.w(TAG, "history could not be read", e);
                rows = Collections.emptyList();
            }
            final List<HistoryRows.Row> result = rows;
            mainHandler.post(() -> callback.accept(result));
        });
    }

    public void remove(final String url) {
        if (url == null) return;
        io.execute(() -> {
            try {
                dao.deleteByUrl(url);
            } catch (Exception e) {
                Log.w(TAG, "remove failed", e);
            }
        });
    }

    public void removeVisit(final long id) {
        if (id <= 0) return;
        io.execute(() -> {
            try {
                dao.deleteById(id);
            } catch (Exception e) {
                Log.w(TAG, "removeVisit failed", e);
            }
        });
    }

    public void clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearAsync();
            return;
        }
        try {
            dao.deleteAll();
        } catch (Exception e) {
            Log.w(TAG, "clear failed", e);
        }
    }

    public void clearAsync() {
        io.execute(() -> {
            try {
                dao.deleteAll();
            } catch (Exception e) {
                Log.w(TAG, "clear failed", e);
            }
        });
    }

    /**
     * Waits briefly for already-queued history writes to finish without shutting down the
     * process-wide singleton executors.
     */
    public void flush(long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        io.execute(latch::countDown);
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<HistoryStore.Entry> toEntries(List<HistoryDao.HistorySummary> summaries) {
        if (summaries == null || summaries.isEmpty()) return new ArrayList<>();
        final List<HistoryStore.Entry> out = new ArrayList<>(summaries.size());
        for (HistoryDao.HistorySummary s : summaries) {
            if (s == null || s.url == null) continue;
            out.add(new HistoryStore.Entry(
                    s.url,
                    s.title == null ? "" : s.title,
                    s.lastVisited,
                    s.visitCount));
        }
        return out;
    }
}
