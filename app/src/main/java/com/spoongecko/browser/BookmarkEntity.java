package com.spoongecko.browser;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a saved bookmark for a URL from a browsing session.
 */
@Entity(
        tableName = "bookmarks",
        indices = {@Index(value = "url", unique = true)}
)
public class BookmarkEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "url")
    public String url = "";

    @NonNull
    @ColumnInfo(name = "title")
    public String title = "";

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public BookmarkEntity() {
    }

    @Ignore
    public BookmarkEntity(@NonNull String url, @NonNull String title, long createdAt) {
        this.url = url;
        this.title = title;
        this.createdAt = createdAt;
    }
}
