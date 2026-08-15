package com.spoongecko.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class BrowserDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "browser_data.db";
    private static final int DB_VERSION = 1;

    BrowserDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS history");
        db.execSQL("DROP TABLE IF EXISTS bookmarks");
        onCreate(db);
    }
}
