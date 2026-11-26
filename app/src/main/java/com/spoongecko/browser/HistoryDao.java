package com.spoongecko.browser;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

/**
 * Room Data Access Object for persistent browser history visits.
 */
@Dao
public interface HistoryDao {

    /**
     * Aggregated per-URL summary computed from recorded visit rows.
     */
    class HistorySummary {
        public String url = "";
        public String title = "";
        public long lastVisited;
        public int visitCount;
    }

    @Insert
    long insert(HistoryEntity entry);

    @Insert
    void insertAll(List<HistoryEntity> entries);

    @Query("SELECT COUNT(*) FROM history_entries")
    int countAll();

    @Query("SELECT * FROM history_entries ORDER BY visited_at DESC, id DESC LIMIT :limit")
    List<HistoryEntity> getRecentVisits(int limit);

    @Query("SELECT * FROM history_entries ORDER BY visited_at ASC, id ASC LIMIT :limit")
    List<HistoryEntity> getOldestVisits(int limit);

    @Query("SELECT h.url AS url, "
            + "COALESCE((SELECT h2.title FROM history_entries h2 "
            + "WHERE h2.url = h.url ORDER BY h2.visited_at DESC, h2.id DESC LIMIT 1), '') AS title, "
            + "MAX(h.visited_at) AS lastVisited, "
            + "COUNT(*) AS visitCount "
            + "FROM history_entries h "
            + "GROUP BY h.url "
            + "ORDER BY lastVisited DESC "
            + "LIMIT :limit")
    List<HistorySummary> getRecentSummaries(int limit);

    @Query("SELECT h.url AS url, "
            + "COALESCE((SELECT h2.title FROM history_entries h2 "
            + "WHERE h2.url = h.url ORDER BY h2.visited_at DESC, h2.id DESC LIMIT 1), '') AS title, "
            + "MAX(h.visited_at) AS lastVisited, "
            + "COUNT(*) AS visitCount "
            + "FROM history_entries h "
            + "GROUP BY h.url "
            + "ORDER BY lastVisited ASC "
            + "LIMIT :limit")
    List<HistorySummary> getOldestSummaries(int limit);

    @RawQuery
    List<HistorySummary> querySummaries(SupportSQLiteQuery query);

    @Query("DELETE FROM history_entries WHERE url = :url")
    int deleteByUrl(String url);

    @Query("DELETE FROM history_entries WHERE id = :id")
    int deleteById(long id);

    @Query("DELETE FROM history_entries")
    void deleteAll();
}
