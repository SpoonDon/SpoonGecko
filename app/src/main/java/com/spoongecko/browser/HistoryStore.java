package com.spoongecko.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class HistoryStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "spoongecko_history.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "history";

    public static class Entry {
        public final String url;
        public final String title;
        public Entry(String url, String title) { this.url = url; this.title = title; }
    }

    public HistoryStore(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
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
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("url", url);
        cv.put("title", title == null ? "" : title);
        cv.put("last_visited", System.currentTimeMillis());
        long id = db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            db.execSQL("UPDATE " + TABLE + " SET visit_count = visit_count + 1, " +
                            "last_visited = ?, title = ? WHERE url = ?",
                    new Object[]{System.currentTimeMillis(), title == null ? "" : title, url});
        }
    }

    public List<Entry> suggest(String query, int limit) {
        List<Entry> out = new ArrayList<>();
        if (query == null || query.isEmpty()) return out;
        String like = "%" + query + "%";
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT url, title FROM " + TABLE +
                        " WHERE url LIKE ? OR title LIKE ?" +
                        " ORDER BY visit_count DESC, last_visited DESC LIMIT ?",
                new String[]{like, like, String.valueOf(limit)});
        while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1)));
        c.close();
        return out;
    }

    public List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT url, title FROM " + TABLE +
                        " ORDER BY last_visited DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1)));
        c.close();
        return out;
    }

    public void clear() { getWritableDatabase().delete(TABLE, null, null); }
}
