package com.spoongecko.browser;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fetches real-time search query predictions from DuckDuckGo's suggest API on a background
 * thread without keeping an idle thread alive permanently.
 */
final class SearchSuggestionsClient {

    private static final String TAG = "SpoonGecko";
    private static final String ENDPOINT_PREFIX = "https://duckduckgo.com/ac/?type=list&q=";
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger activeToken = new AtomicInteger(0);

    SearchSuggestionsClient() {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            final Thread t = new Thread(r, "spoongecko-suggest-net");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        this.executor = pool;
    }

    /** Returns false for explicit URIs or local host/IP targets that should not leave the device. */
    static boolean shouldFetchRemote(String query) {
        if (query == null) return false;
        final String trimmed = query.trim();
        if (trimmed.isEmpty() || trimmed.length() > SuggestionRanker.MAX_QUERY_CHARS) return false;
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("://")
                || lower.startsWith("about:")
                || lower.startsWith("data:")
                || lower.startsWith("file:")
                || lower.startsWith("javascript:")) {
            return false;
        }
        final LocalAddress.Authority auth = LocalAddress.parseAuthority(trimmed);
        if (auth != null && (LocalAddress.isIpLiteral(auth.host) || "localhost".equals(auth.host))) {
            return false;
        }
        return true;
    }

    void cancelPending() {
        activeToken.incrementAndGet();
    }

    void fetchAsync(final String query, final int limit, final Consumer<List<String>> callback) {
        if (!shouldFetchRemote(query) || limit <= 0) {
            if (callback != null) mainHandler.post(() -> callback.accept(Collections.emptyList()));
            return;
        }
        final String trimmed = query.trim();
        final int token = activeToken.incrementAndGet();
        try {
            executor.execute(() -> {
                if (token != activeToken.get()) return;
                final List<String> suggestions = fetchSync(trimmed, limit, token);
                if (token != activeToken.get()) return;
                if (callback != null) {
                    mainHandler.post(() -> {
                        if (token == activeToken.get()) {
                            callback.accept(suggestions);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Client already shut down.
        }
    }

    private List<String> fetchSync(String query, int limit, int token) {
        HttpURLConnection conn = null;
        try {
            final URL url = new URL(ENDPOINT_PREFIX + Uri.encode(query));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SpoonGecko");

            if (token != activeToken.get()) return Collections.emptyList();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return Collections.emptyList();
            }

            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int total = 0;
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (token != activeToken.get()) return Collections.emptyList();
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) break;
                    bos.write(buf, 0, n);
                }
            }
            return parseResponse(new String(bos.toByteArray(), StandardCharsets.UTF_8), limit);
        } catch (Exception e) {
            Log.d(TAG, "search suggest fetch skipped: " + e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Parses both DuckDuckGo OpenSearch format ({@code ["query", ["a", "b"]]}) and the legacy
     * object-array format ({@code [{"phrase":"a"}]}).
     */
    static List<String> parseResponse(String json, int limit) {
        if (json == null || json.isEmpty() || limit <= 0) return Collections.emptyList();
        try {
            final JSONArray root = new JSONArray(json);
            final Set<String> dedup = new LinkedHashSet<>();

            if (root.length() >= 2 && root.optJSONArray(1) != null) {
                final JSONArray items = root.getJSONArray(1);
                for (int i = 0; i < items.length() && dedup.size() < limit; i++) {
                    final String val = items.optString(i, "").trim();
                    if (!val.isEmpty()) dedup.add(val);
                }
            } else {
                for (int i = 0; i < root.length() && dedup.size() < limit; i++) {
                    final JSONObject obj = root.optJSONObject(i);
                    if (obj == null) continue;
                    final String val = obj.optString("phrase", "").trim();
                    if (!val.isEmpty()) dedup.add(val);
                }
            }
            return dedup.isEmpty() ? Collections.emptyList() : new ArrayList<>(dedup);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    void shutdown() {
        activeToken.incrementAndGet();
        executor.shutdownNow();
    }
}
