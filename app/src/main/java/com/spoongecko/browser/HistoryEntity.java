package com.spoongecko.browser;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a single completed page visit in browser history.
 */
@Entity(
        tableName = "history_entries",
        indices = {
                @Index(value = "url"),
                @Index(value = "visited_at")
        }
)
public class HistoryEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "url")
    public String url = "";

    @NonNull
    @ColumnInfo(name = "title")
    public String title = "";

    @ColumnInfo(name = "visited_at")
    public long visitedAt;

    public HistoryEntity() {
    }

    @Ignore
    public HistoryEntity(@NonNull String url, @NonNull String title, long visitedAt) {
        this.url = url;
        this.title = title;
        this.visitedAt = visitedAt;
    }
}
