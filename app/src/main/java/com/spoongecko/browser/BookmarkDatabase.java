package com.spoongecko.browser;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Process-wide Room database storing user bookmarks locally.
 */
@Database(entities = {BookmarkEntity.class}, version = 1, exportSchema = false)
public abstract class BookmarkDatabase extends RoomDatabase {

    private static final String DB_NAME = "spoongecko_bookmarks.db";
    private static volatile BookmarkDatabase instance;

    public abstract BookmarkDao bookmarkDao();

    public static BookmarkDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (BookmarkDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            BookmarkDatabase.class,
                            DB_NAME
                    ).build();
                }
            }
        }
        return instance;
    }
}
