package com.spoongecko.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Repository mediating access between UI dialogs/menus and the Room {@link BookmarkDao}.
 * All database operations run on a single serialized background thread and deliver results on
 * the main thread.
 */
public final class BookmarkRepository {

    private static final String TAG = "SpoonGecko";
    private static volatile BookmarkRepository instance;

    private final BookmarkDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "spoongecko-bookmarks");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> cachedUrls = ConcurrentHashMap.newKeySet();

    private BookmarkRepository(Context context) {
        this.dao = BookmarkDatabase.getInstance(context).bookmarkDao();
        warmCache();
    }

    public static BookmarkRepository get(Context context) {
        if (instance == null) {
            synchronized (BookmarkRepository.class) {
                if (instance == null) {
                    instance = new BookmarkRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private void warmCache() {
        io.execute(() -> {
            try {
                final List<String> urls = dao.getAllUrls();
                cachedUrls.clear();
                if (urls != null) {
                    cachedUrls.addAll(urls);
                }
            } catch (Exception e) {
                Log.w(TAG, "bookmark cache warm-up failed", e);
            }
        });
    }

    public static boolean isBookmarkable(String url, String title) {
        if (url == null) return false;
        final String trimmed = url.trim();
        if (trimmed.isEmpty()) return false;
        if ("about:blank".equalsIgnoreCase(trimmed)) return false;
        if (ErrorPages.isErrorPageUri(trimmed)) return false;
        return !ErrorPages.isErrorTitle(title);
    }

    public boolean isCachedBookmarked(String url) {
        if (url == null) return false;
        return cachedUrls.contains(url.trim());
    }

    public void loadAll(final Consumer<List<BookmarkEntity>> callback) {
        io.execute(() -> {
            List<BookmarkEntity> items;
            try {
                items = dao.getAllOrdered();
                final Set<String> fresh = new HashSet<>(items.size());
                for (BookmarkEntity item : items) {
                    fresh.add(item.url);
                }
                cachedUrls.clear();
                cachedUrls.addAll(fresh);
            } catch (Exception e) {
                Log.w(TAG, "bookmarks load failed", e);
                items = Collections.emptyList();
            }
            final List<BookmarkEntity> result = items;
            if (callback != null) {
                mainHandler.post(() -> callback.accept(result));
            }
        });
    }

    public void saveBookmark(final String rawUrl, final String rawTitle,
                             final Consumer<Boolean> callback) {
        if (!isBookmarkable(rawUrl, rawTitle)) {
            if (callback != null) mainHandler.post(() -> callback.accept(false));
            return;
        }
        final String url = rawUrl.trim();
        final String title = normalizeTitle(rawTitle, url);
        io.execute(() -> {
            boolean ok = false;
            try {
                dao.insertOrReplace(new BookmarkEntity(url, title, System.currentTimeMillis()));
                cachedUrls.add(url);
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "saveBookmark failed", e);
            }
            final boolean saved = ok;
            if (callback != null) {
                mainHandler.post(() -> callback.accept(saved));
            }
        });
    }

    /**
     * Toggles the bookmark state for {@code rawUrl}. Delivers {@code true} if saved,
     * {@code false} if removed, or {@code null} if the URL cannot be bookmarked or failed.
     */
    public void toggleBookmark(final String rawUrl, final String rawTitle,
                               final Consumer<Boolean> callback) {
        if (!isBookmarkable(rawUrl, rawTitle)) {
            if (callback != null) mainHandler.post(() -> callback.accept(null));
            return;
        }
        final String url = rawUrl.trim();
        final String title = normalizeTitle(rawTitle, url);
        io.execute(() -> {
            Boolean resultState = null;
            try {
                if (dao.exists(url)) {
                    dao.deleteByUrl(url);
                    cachedUrls.remove(url);
                    resultState = Boolean.FALSE;
                } else {
                    dao.insertOrReplace(new BookmarkEntity(url, title, System.currentTimeMillis()));
                    cachedUrls.add(url);
                    resultState = Boolean.TRUE;
                }
            } catch (Exception e) {
                Log.w(TAG, "toggleBookmark failed", e);
            }
            final Boolean finalState = resultState;
            if (callback != null) {
                mainHandler.post(() -> callback.accept(finalState));
            }
        });
    }

    public void deleteBookmark(final BookmarkEntity bookmark, final Runnable onDone) {
        if (bookmark == null) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        io.execute(() -> {
            try {
                if (bookmark.id > 0) {
                    dao.deleteById(bookmark.id);
                } else {
                    dao.deleteByUrl(bookmark.url);
                }
                cachedUrls.remove(bookmark.url);
            } catch (Exception e) {
                Log.w(TAG, "deleteBookmark failed", e);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void clearAll(final Runnable onDone) {
        io.execute(() -> {
            try {
                dao.deleteAll();
                cachedUrls.clear();
            } catch (Exception e) {
                Log.w(TAG, "clearAll bookmarks failed", e);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    private static String normalizeTitle(String rawTitle, String fallbackUrl) {
        if (rawTitle == null) return SuggestionRanker.shortUrl(fallbackUrl);
        final String trimmed = rawTitle.trim();
        return trimmed.isEmpty() ? SuggestionRanker.shortUrl(fallbackUrl) : trimmed;
    }
}
