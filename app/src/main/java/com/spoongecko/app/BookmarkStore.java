package com.spoongecko.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class BookmarkStore {

    private static final String TAG = "BookmarkStore";

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
        } catch (Exception e) {
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

        Cursor cursor = db.query("bookmarks",
                new String[]{"_id", "url", "title", "added_at"},
                selection, args, null, null, "added_at DESC", null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                entries.add(new Entry(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getLong(3)));
            }
            cursor.close();
        }
        return entries;
    }

    public static void delete(Context context, long id) {
        if (context == null) return;
        try {
            SQLiteDatabase db = BrowserDatabase.getInstance(context).getWritableDatabase();
            db.delete("bookmarks", "_id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "delete failed", e);
        }
    }
}
