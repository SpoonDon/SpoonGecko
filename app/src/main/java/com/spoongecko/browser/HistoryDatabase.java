package com.spoongecko.browser;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Process-wide Room database storing browser history visits locally.
 */
@Database(entities = {HistoryEntity.class}, version = 1, exportSchema = false)
public abstract class HistoryDatabase extends RoomDatabase {

    private static final String DB_NAME = "spoongecko_history_room.db";
    private static volatile HistoryDatabase instance;

    public abstract HistoryDao historyDao();

    public static HistoryDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (HistoryDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    HistoryDatabase.class,
                                    DB_NAME)
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .build();
                }
            }
        }
        return instance;
    }
}
