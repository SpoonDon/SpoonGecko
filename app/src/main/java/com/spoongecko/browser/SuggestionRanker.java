package com.spoongecko.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * [SUGGEST] Turns what was typed in the address bar into a clean search, and orders the history
 * entries that match it. Pure Java (no Android classes) so it can be tested on its own.
 *
 * What it does that the old "url LIKE %q% OR title LIKE %q% ORDER BY visit_count" query did not:
 *  - Several words all have to match, in any order ("wiki cats" finds "Cats - Wikipedia").
 *  - "https://" and "www." in what was typed are ignored, so "www.goo" finds google.com.
 *  - Better matches come first: a site whose NAME starts with the text ("git" -> github.com)
 *    beats a page that merely contains it somewhere. Popularity and recency only order matches
 *    of the same quality.
 *  - The same page saved under several addresses (http/https, with/without "www.", with/without
 *    a trailing "/") is listed once.
 */
final class SuggestionRanker {

    /** Longer input is a pasted address or text: nothing useful to suggest, so no query is run. */
    static final int MAX_QUERY_CHARS = 200;
    /** Words beyond this are ignored (keeps the SQL and the work bounded). */
    static final int MAX_TOKENS = 5;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SuggestionRanker() { }

    /** The cleaned-up words of a search. */
    static final class Query {
        /** Lower case, no "http(s)://", no leading "www."; never empty, never blank. */
        final List<String> tokens;

        Query(List<String> tokens) { this.tokens = tokens; }
    }

    /**
     * @return null when there is nothing sensible to look up: empty or blank input, only a
     *         scheme / "www." typed so far, or input far too long.
     */
    static Query parse(String raw) {
        if (raw == null) return null;
        final String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_QUERY_CHARS) return null;
        final List<String> tokens = new ArrayList<>();
        for (String part : WHITESPACE.split(s.toLowerCase(Locale.ROOT))) {
            final String t = stripSchemeAndWww(part);
            if (!t.isEmpty()) tokens.add(t);
            if (tokens.size() == MAX_TOKENS) break;
        }
        return tokens.isEmpty() ? null : new Query(tokens);
    }

    /** Escapes LIKE wildcards so "my_site" and "100%" are matched literally (use with ESCAPE '\'). */
    static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ------------------------------------------------------------------
    // Address helpers (shared with the history list)
    // ------------------------------------------------------------------

    /** "https://www.example.com/a/" becomes "example.com/a": how an address is shown. */
    static String shortUrl(String url) {
        if (url == null) return "";
        String s = stripSchemeAndWww(url.trim());
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The site part of an address ("example.com", "example.com:8080"), lower case; "" if none. */
    static String hostOf(String url) {
        final String s = shortUrl(url);
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String host = s.substring(0, end);
        final int at = host.lastIndexOf('@');          // user:password@host
        if (at >= 0) host = host.substring(at + 1);
        // "about:blank", "data:..." and the like have no site.
        if (url != null && !url.contains("://") && host.indexOf('.') < 0 && host.indexOf(':') >= 0) {
            return "";
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** Two addresses with the same key are the same page for the purpose of a list. */
    static String key(String url) {
        final String s = shortUrl(url);
        final String host = hostOf(url);
        if (host.isEmpty()) return s;
        final int hostEnd = s.toLowerCase(Locale.ROOT).indexOf(host) + host.length();
        return host + (hostEnd <= s.length() ? s.substring(hostEnd) : "");
    }

    private static String stripSchemeAndWww(String s) {
        String r = s;
        final String lower = r.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) r = r.substring(8);
        else if (lower.startsWith("http://")) r = r.substring(7);
        if (r.toLowerCase(Locale.ROOT).startsWith("www.")) r = r.substring(4);
        return r;
    }

    // ------------------------------------------------------------------
    // Ranking
    // ------------------------------------------------------------------

    private static final class Scored {
        final HistoryStore.Entry entry;
        final double total;
        final String shortUrl;
        final boolean https;

        Scored(HistoryStore.Entry entry, double total, String shortUrl, boolean https) {
            this.entry = entry;
            this.total = total;
            this.shortUrl = shortUrl;
            this.https = https;
        }
    }

    /**
     * Keeps the candidates that match every word, orders them (match quality first, then how
     * often and how recently the page was visited), lists each page once and cuts the list to
     * {@code limit}.
     */
    static List<HistoryStore.Entry> rank(List<HistoryStore.Entry> candidates, Query q,
                                         int limit, long nowMs) {
        final List<HistoryStore.Entry> out = new ArrayList<>();
        if (candidates == null || q == null || limit <= 0) return out;

        final List<Scored> scored = new ArrayList<>();
        for (HistoryStore.Entry e : candidates) {
            if (e == null || e.url == null || e.url.isEmpty()) continue;
            final String shortUrl = shortUrl(e.url);
            final String u = shortUrl.toLowerCase(Locale.ROOT);
            final String h = hostOf(e.url);
            final String ti = e.title == null ? "" : e.title.toLowerCase(Locale.ROOT);

            double sum = 0;
            boolean all = true;
            for (String t : q.tokens) {
                final int s = tokenScore(t, h, u, ti);
                if (s < 0) { all = false; break; }
                sum += s;
            }
            if (!all) continue;
            final double total = sum / q.tokens.size() + frecency(e, nowMs);
            scored.add(new Scored(e, total, shortUrl,
                    e.url.toLowerCase(Locale.ROOT).startsWith("https://")));
        }

        Collections.sort(scored, new Comparator<Scored>() {
            @Override public int compare(Scored a, Scored b) {
                int c = Double.compare(b.total, a.total);
                if (c != 0) return c;
                if (a.https != b.https) return a.https ? -1 : 1;
                c = Integer.compare(a.shortUrl.length(), b.shortUrl.length());
                if (c != 0) return c;
                return a.entry.url.compareTo(b.entry.url);
            }
        });

        final Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Scored s : scored) {
            if (seen.put(key(s.entry.url), Boolean.TRUE) != null) continue;   // already listed
            out.add(s.entry);
            if (out.size() == limit) break;
        }
        return out;
    }

    /**
     * How well one word matches a page; -1 = not at all. The steps are 20 apart, and
     * {@link #frecency} never exceeds 15, so popularity can only reorder pages that match
     * equally well.
     */
    private static int tokenScore(String t, String host, String url, String title) {
        if (host.startsWith(t)) return 100;            // "git"  -> github.com
        if (url.startsWith(t)) return 80;              // "github.com/sp" -> github.com/spoon...
        if (host.contains("." + t) || host.contains("-" + t)) return 60;   // "python" -> docs.python.org
        if (startsAWord(title, t)) return 40;          // "wiki" -> "Cats - Wikipedia"
        if (url.contains(t) || title.contains(t)) return 20;
        return -1;
    }

    private static boolean startsAWord(String text, String t) {
        int from = 0;
        while (true) {
            final int i = text.indexOf(t, from);
            if (i < 0) return false;
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) return true;
            from = i + 1;
        }
    }

    /** 0..15: grows with the number of visits and with how recently the page was visited. */
    private static double frecency(HistoryStore.Entry e, long nowMs) {
        final double visits = Math.log(1.0 + Math.max(0, e.visitCount)) / Math.log(2.0) * 3.0;
        double recency = 0;
        if (e.lastVisited > 0) {
            final long age = nowMs - e.lastVisited;
            recency = age < DAY_MS ? 6 : age < 7 * DAY_MS ? 4 : age < 30 * DAY_MS ? 2 : 0;
        }
        return Math.min(15.0, visits + recency);
    }
}
