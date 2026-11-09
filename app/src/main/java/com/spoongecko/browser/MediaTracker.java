package com.spoongecko.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Bookkeeping for the pages (GeckoSessions) that currently have a media
 * session, and the decision of which one the notification should describe.
 *
 * No Android and no GeckoView dependencies: K is whatever identifies a page
 * (a GeckoSession in the app, a String in the tests). All methods are meant to
 * be called from one thread (the UI thread).
 *
 * Selection rule for current(): a playing page always wins over a paused one;
 * among equals the page whose state changed most recently wins. Pages that
 * reported "stopped" are never selected.
 */
final class MediaTracker<K> {

    static final class Entry<K> {
        final K key;
        boolean playing;
        boolean stopped;
        String title;
        String artist;
        String album;
        /** MediaSession.Feature bit set, or null until the page reported one. */
        Long features;
        long seq;

        Entry(K key) {
            this.key = key;
        }
    }

    private final Map<K, Entry<K>> entries = new LinkedHashMap<>();
    private long clock;

    private Entry<K> obtain(K key) {
        Entry<K> e = entries.get(key);
        if (e == null) {
            e = new Entry<>(key);
            entries.put(key, e);
        }
        e.seq = ++clock;
        return e;
    }

    Entry<K> activated(K key) {
        final Entry<K> e = obtain(key);
        e.stopped = false;
        return e;
    }

    Entry<K> play(K key) {
        final Entry<K> e = obtain(key);
        e.playing = true;
        e.stopped = false;
        return e;
    }

    /** Only affects a page that is still tracked: a late "pause" after deactivation must not resurrect it. */
    Entry<K> pause(K key) {
        final Entry<K> e = entries.get(key);
        if (e == null) return null;
        e.seq = ++clock;
        e.playing = false;
        return e;
    }

    /** Same rule as {@link #pause}. */
    Entry<K> stop(K key) {
        final Entry<K> e = entries.get(key);
        if (e == null) return null;
        e.seq = ++clock;
        e.playing = false;
        e.stopped = true;
        return e;
    }

    /** The page's media session ended: forget it. */
    void deactivated(K key) {
        entries.remove(key);
    }

    void metadata(K key, String title, String artist, String album) {
        final Entry<K> e = entries.get(key);
        if (e == null) return;
        e.title = title;
        e.artist = artist;
        e.album = album;
    }

    void features(K key, long features) {
        final Entry<K> e = entries.get(key);
        if (e != null) e.features = features;
    }

    /** Drops every entry whose key is no longer alive (e.g. a closed tab). Returns true if any was dropped. */
    boolean prune(Predicate<K> alive) {
        final List<K> dead = new ArrayList<>();
        for (K k : entries.keySet()) if (!alive.test(k)) dead.add(k);
        for (K k : dead) entries.remove(k);
        return !dead.isEmpty();
    }

    void clear() {
        entries.clear();
    }

    Entry<K> get(K key) {
        return entries.get(key);
    }

    boolean isPlaying(K key) {
        final Entry<K> e = entries.get(key);
        return e != null && e.playing;
    }

    boolean anyPlaying() {
        for (Entry<K> e : entries.values()) if (e.playing) return true;
        return false;
    }

    /** The entry the notification should describe, or null if there is none. */
    Entry<K> current() {
        Entry<K> bestPlaying = null;
        Entry<K> bestAny = null;
        for (Entry<K> e : entries.values()) {
            if (e.stopped) continue;
            if (bestAny == null || e.seq > bestAny.seq) bestAny = e;
            if (e.playing && (bestPlaying == null || e.seq > bestPlaying.seq)) bestPlaying = e;
        }
        return bestPlaying != null ? bestPlaying : bestAny;
    }

    /**
     * The most recently active entry even if it reported "stopped". While the
     * service is lingering after a stop (the gap between two tracks) this is
     * the page that must keep its process priority.
     */
    Entry<K> latest() {
        Entry<K> best = null;
        for (Entry<K> e : entries.values()) if (best == null || e.seq > best.seq) best = e;
        return best;
    }

    /** Playing entries, oldest first. */
    List<Entry<K>> playingEntries() {
        final List<Entry<K>> out = new ArrayList<>();
        for (Entry<K> e : entries.values()) if (e.playing) out.add(e);
        return out;
    }

    /**
     * Text for the notification title: the page's media title, else the page
     * title, else the site's host, else a generic label. Blank and placeholder
     * values are skipped.
     */
    static String displayTitle(String mediaTitle, String pageTitle, String host) {
        if (usable(mediaTitle)) return mediaTitle.trim();
        if (usable(pageTitle)) return pageTitle.trim();
        if (usable(host)) return host.trim();
        return "Playing media";
    }

    private static boolean usable(String s) {
        return s != null && !s.trim().isEmpty() && !"New tab".equals(s.trim());
    }
}
