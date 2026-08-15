package com.spoongecko.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {

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
        if (context == null || url == null || url.isEmpty() || url.startsWith("data:") || url.startsWith("about:")) return;

        BrowserDatabase helper = new BrowserDatabase(context);
        SQLiteDatabase db = helper.getWritableDatabase();

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
        db.close();
    }

    public static List<Entry> query(Context context, String search, int limit) {
        List<Entry> entries = new ArrayList<>();
        BrowserDatabase helper = new BrowserDatabase(context);
        SQLiteDatabase db = helper.getReadableDatabase();

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
        db.close();
        return entries;
    }

    public static void delete(Context context, long id) {
        BrowserDatabase helper = new BrowserDatabase(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("history", "_id=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public static void clear(Context context) {
        BrowserDatabase helper = new BrowserDatabase(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("history", null, null);
        db.close();
    }
}
