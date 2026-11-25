package com.spoongecko.browser;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Room Data Access Object for persistent bookmarks.
 */
@Dao
public interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC, id DESC")
    List<BookmarkEntity> getAllOrdered();

    @Query("SELECT url FROM bookmarks")
    List<String> getAllUrls();

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    boolean exists(String url);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrReplace(BookmarkEntity bookmark);

    @Query("DELETE FROM bookmarks WHERE id = :id")
    int deleteById(long id);

    @Query("DELETE FROM bookmarks WHERE url = :url")
    int deleteByUrl(String url);

    @Query("DELETE FROM bookmarks")
    void deleteAll();
}
