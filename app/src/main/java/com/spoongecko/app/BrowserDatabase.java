package com.spoongecko.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class BrowserDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "browser_data.db";
    private static final int DB_VERSION = 3;
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

        createFts(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_visited_at ON history(visited_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_added_at ON bookmarks(added_at)");
        }
        if (oldVersion < 3) {
            createFts(db);
            db.execSQL("INSERT INTO history_fts(history_fts) VALUES('rebuild')");
            db.execSQL("INSERT INTO bookmarks_fts(bookmarks_fts) VALUES('rebuild')");
        }
    }

    private void createFts(SQLiteDatabase db) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS history_fts "
                + "USING fts4(content='history', url, title)");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS bookmarks_fts "
                + "USING fts4(content='bookmarks', url, title)");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS history_fts_bu BEFORE UPDATE ON history BEGIN "
                + "DELETE FROM history_fts WHERE docid=old._id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS history_fts_bd BEFORE DELETE ON history BEGIN "
                + "DELETE FROM history_fts WHERE docid=old._id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS history_fts_ai AFTER INSERT ON history BEGIN "
                + "INSERT INTO history_fts(docid, url, title) VALUES (new._id, new.url, new.title); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS history_fts_au AFTER UPDATE ON history BEGIN "
                + "INSERT INTO history_fts(docid, url, title) VALUES (new._id, new.url, new.title); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS bookmarks_fts_bu BEFORE UPDATE ON bookmarks BEGIN "
                + "DELETE FROM bookmarks_fts WHERE docid=old._id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS bookmarks_fts_bd BEFORE DELETE ON bookmarks BEGIN "
                + "DELETE FROM bookmarks_fts WHERE docid=old._id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS bookmarks_fts_ai AFTER INSERT ON bookmarks BEGIN "
                + "INSERT INTO bookmarks_fts(docid, url, title) VALUES (new._id, new.url, new.title); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS bookmarks_fts_au AFTER UPDATE ON bookmarks BEGIN "
                + "INSERT INTO bookmarks_fts(docid, url, title) VALUES (new._id, new.url, new.title); END");
    }
}
