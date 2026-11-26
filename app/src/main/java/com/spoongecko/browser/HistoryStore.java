package com.spoongecko.browser;

import android.content.Context;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Facade delegating browser history persistence and queries to {@link HistoryRepository} (Room).
 */
public class HistoryStore {

    public static class Entry {
        public final String url;
        public final String title;
        /** When the page was last visited (milliseconds since 1970); 0 = unknown (old data). */
        public final long lastVisited;
        /** How many times the page was visited; 0 = unknown. */
        public final int visitCount;

        public Entry(String url, String title) { this(url, title, 0L, 0); }

        public Entry(String url, String title, long lastVisited, int visitCount) {
            this.url = url;
            this.title = title;
            this.lastVisited = lastVisited;
            this.visitCount = visitCount;
        }
    }

    /** Receives asynchronous query results on the UI thread. */
    public interface SuggestCallback {
        void onResult(List<Entry> results);
    }

    private final HistoryRepository repository;

    public HistoryStore(Context ctx) {
        this.repository = HistoryRepository.get(ctx);
    }

    public void recordVisit(String url, String title) {
        repository.recordVisit(url, title);
    }

    public void recordVisit(String url, String title, long visitedAt) {
        repository.recordVisit(url, title, visitedAt);
    }

    public List<Entry> suggest(String query, int limit) {
        return repository.suggest(query, limit);
    }

    public void suggestAsync(final String query, final int limit, final SuggestCallback callback) {
        repository.suggestAsync(query, limit, callback);
    }

    /** The most recently visited pages, newest first. */
    public List<Entry> recent(int limit) {
        return repository.recent(limit);
    }

    /** The least recently visited pages, oldest first. */
    public List<Entry> oldest(int limit) {
        return repository.oldest(limit);
    }

    /**
     * Loads history rows, filters out any pending-delete URLs, and builds/sorts
     * {@link HistoryRows.Row} off the UI thread before delivering on the UI thread.
     */
    void loadRowsAsync(final HistoryRows.Mode mode, final int limit,
                       final Set<String> removedSnapshot, final ZoneId zone,
                       final Locale locale, final Consumer<List<HistoryRows.Row>> callback) {
        repository.loadRowsAsync(mode, limit, removedSnapshot, zone, locale, callback);
    }

    public void clear() {
        repository.clear();
    }

    public void clearAsync() {
        repository.clearAsync();
    }

    public void remove(final String url) {
        repository.remove(url);
    }

    public void close() {
        repository.flush(2000L);
    }
}
