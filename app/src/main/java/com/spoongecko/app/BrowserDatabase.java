package com.spoongecko.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class BrowserDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "browser_data.db";
    private static final int DB_VERSION = 2;
    private static volatile BrowserDatabase instance;

    private BrowserDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static BrowserDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (BrowserDatabase.class) {
                if (instance == null) {
                    instance = new BrowserDatabase(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "url TEXT NOT NULL,"
                + "title TEXT,"
                + "visited_at INTEGER NOT NULL,"
                + "visit_count INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE bookmarks ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "url TEXT NOT NULL,"
                + "title TEXT,"
                + "added_at INTEGER NOT NULL)");

        db.execSQL("CREATE INDEX idx_history_visited_at ON history(visited_at)");
        db.execSQL("CREATE INDEX idx_history_url ON history(url)");
        db.execSQL("CREATE INDEX idx_bookmarks_added_at ON bookmarks(added_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_visited_at ON history(visited_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_added_at ON bookmarks(added_at)");
        }
    }
}
