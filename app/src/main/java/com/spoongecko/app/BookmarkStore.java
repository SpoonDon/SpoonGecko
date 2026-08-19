package com.spoongecko.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class BookmarkStore {

    private static final String TAG = "BookmarkStore";
    private static final String[] COLUMNS = {"_id", "url", "title", "added_at"};

    public static final class Entry {
        public final long id;
        public final String url;
        public final String title;
        public final long addedAt;

        Entry(long id, String url, String title, long addedAt) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.addedAt = addedAt;
        }
    }

    private BookmarkStore() {}

    public static boolean add(Context context, String url, String title) {
        if (context == null || url == null || url.isEmpty()) return false;
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("url", url);
            values.put("title", title != null && !title.isEmpty() ? title : url);
            values.put("added_at", System.currentTimeMillis());
            long id = db.insert("bookmarks", null, values);
            return id != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "add failed", e);
            return false;
        }
    }

    public static List<Entry> query(Context context, String search) {
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
                cursor = db.rawQuery(
                        "SELECT b._id, b.url, b.title, b.added_at "
                                + "FROM bookmarks b JOIN bookmarks_fts f ON b._id = f.docid "
                                + "WHERE bookmarks_fts MATCH ? ORDER BY b.added_at DESC",
                        new String[]{match});
            } else {
                cursor = db.query("bookmarks", COLUMNS, null, null, null, null,
                        "added_at DESC", null);
            }

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    entries.add(new Entry(cursor.getLong(0), cursor.getString(1),
                            cursor.getString(2), cursor.getLong(3)));
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
            db.delete("bookmarks", "_id=?", new String[]{String.valueOf(id)});
        } catch (SQLiteException e) {
            Log.e(TAG, "delete failed", e);
        }
    }
}
