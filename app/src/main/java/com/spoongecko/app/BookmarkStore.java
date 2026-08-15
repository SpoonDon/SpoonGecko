package com.spoongecko.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public final class BookmarkStore {

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
        BrowserDatabase helper = new BrowserDatabase(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("url", url);
        values.put("title", title != null && !title.isEmpty() ? title : url);
        values.put("added_at", System.currentTimeMillis());
        long id = db.insert("bookmarks", null, values);
        db.close();
        return id != -1;
    }

    public static List<Entry> query(Context context, String search) {
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
        db.close();
        return entries;
    }

    public static void delete(Context context, long id) {
        BrowserDatabase helper = new BrowserDatabase(context);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("bookmarks", "_id=?", new String[]{String.valueOf(id)});
        db.close();
    }
}
