package com.spoongecko.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryStore {

    private static final String TAG = "HistoryStore";
    private static final String[] COLUMNS = {"_id", "url", "title", "visited_at", "visit_count"};
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "spoon-history-writer");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    public static final class Entry {
        public final long id;
        public final String url;
        public final String title;
        public final long visitedAt;
        public final int visitCount;

        Entry(long id, String url, String title, long visitedAt, int visitCount) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.visitedAt = visitedAt;
            this.visitCount = visitCount;
        }
    }

    private HistoryStore() {}

    public static void record(Context context, String url, String title) {
        if (context == null || url == null || url.isEmpty()
                || url.startsWith("data:") || url.startsWith("about:")) return;
        Context appContext = context.getApplicationContext();
        WRITE_EXECUTOR.execute(() -> recordInternal(appContext, url, title));
    }

    private static void recordInternal(Context context, String url, String title) {
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            long now = System.currentTimeMillis();
            String safeTitle = title != null ? title : "";

            SQLiteStatement update = db.compileStatement(
                    "UPDATE history SET visit_count = visit_count + 1, title = ?, visited_at = ? WHERE url = ?");
            update.bindString(1, safeTitle);
            update.bindLong(2, now);
            update.bindString(3, url);
            int rows = update.executeUpdateDelete();

            if (rows == 0) {
                ContentValues values = new ContentValues();
                values.put("url", url);
                values.put("title", safeTitle);
                values.put("visited_at", now);
                values.put("visit_count", 1);
                db.insert("history", null, values);
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "record failed", e);
        }
    }

    public static List<Entry> query(Context context, String search, int limit) {
        List<Entry> entries = new ArrayList<>();
        if (context == null) return entries;

        SQLiteDatabase db;
        try {
            db = BrowserDatabase.getInstance(context).getReadableDatabase();
        } catch (SQLiteException e) {
            Log.e(TAG, "query open failed", e);
            return entries;
        }

        Cursor cursor = null;
        try {
            if (search != null && !search.trim().isEmpty()) {
                String match = FtsQuery.match(search);
                String limitClause = limit > 0 ? " LIMIT " + limit : "";
                cursor = db.rawQuery(
                        "SELECT h._id, h.url, h.title, h.visited_at, h.visit_count "
                                + "FROM history h JOIN history_fts f ON h._id = f.docid "
                                + "WHERE history_fts MATCH ? ORDER BY h.visited_at DESC" + limitClause,
                        new String[]{match});
            } else {
                cursor = db.query("history", COLUMNS, null, null, null, null,
                        "visited_at DESC", limit > 0 ? String.valueOf(limit) : null);
            }

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    entries.add(new Entry(cursor.getLong(0), cursor.getString(1),
                            cursor.getString(2), cursor.getLong(3), cursor.getInt(4)));
                }
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "query failed", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return entries;
    }

    public static void delete(Context context, long id) {
        if (context == null) return;
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            db.delete("history", "_id=?", new String[]{String.valueOf(id)});
        } catch (SQLiteException e) {
            Log.e(TAG, "delete failed", e);
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            db.delete("history", null, null);
        } catch (SQLiteException e) {
            Log.e(TAG, "clear failed", e);
        }
    }
}
