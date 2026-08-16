package com.spoongecko.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryStore {

    private static final String TAG = "HistoryStore";
    private static final ExecutorService WRITE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
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
            Cursor cursor = db.query("history", new String[]{"_id", "visit_count"},
                    "url=?", new String[]{url}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                int count = cursor.getInt(1) + 1;
                ContentValues values = new ContentValues();
                values.put("title", title != null ? title : "");
                values.put("visited_at", System.currentTimeMillis());
                values.put("visit_count", count);
                db.update("history", values, "_id=?", new String[]{String.valueOf(id)});
            } else {
                ContentValues values = new ContentValues();
                values.put("url", url);
                values.put("title", title != null ? title : "");
                values.put("visited_at", System.currentTimeMillis());
                values.put("visit_count", 1);
                db.insert("history", null, values);
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "record failed", e);
        }
    }

    public static List<Entry> query(Context context, String search, int limit) {
        List<Entry> entries = new ArrayList<>();
        if (context == null) return entries;
        SQLiteDatabase db;
        try {
            db = BrowserDatabase.getInstance(context).getReadableDatabase();
        } catch (Exception e) {
            Log.e(TAG, "query failed to open db", e);
            return entries;
        }

        String selection = null;
        String[] args = null;
        if (search != null && !search.isEmpty()) {
            String like = "%" + search + "%";
            selection = "url LIKE ? OR title LIKE ?";
            args = new String[]{like, like};
        }

        Cursor cursor = db.query("history",
                new String[]{"_id", "url", "title", "visited_at", "visit_count"},
                selection, args, null, null, "visited_at DESC",
                limit > 0 ? String.valueOf(limit) : null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                entries.add(new Entry(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getLong(3), cursor.getInt(4)));
            }
            cursor.close();
        }
        return entries;
    }

    public static void delete(Context context, long id) {
        if (context == null) return;
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            db.delete("history", "_id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "delete failed", e);
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            db.delete("history", null, null);
        } catch (Exception e) {
            Log.e(TAG, "clear failed", e);
        }
    }
}
