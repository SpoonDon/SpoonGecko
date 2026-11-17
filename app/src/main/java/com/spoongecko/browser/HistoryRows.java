package com.spoongecko.browser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * [HISTORY] Orders the history list and cuts it into sections for the history dialog.
 * Pure Java (no Android classes) so it can be tested on its own.
 *
 *  - NEWEST: latest visit first, with a heading per day ("Today", "Yesterday", the date).
 *  - OLDEST: earliest visit first, same headings.
 *  - SITE:   grouped by site, A to Z, with a heading per site and its page count; the pages of a
 *            site are newest first. Pages that have no site (about:, data:) come last.
 */
final class HistoryRows {

    enum Mode { NEWEST, OLDEST, SITE }

    /** One line of the list: either a section heading or a page. */
    static final class Row {
        /** Not null: this line is a section heading. */
        final String header;
        /** Not null: this line is a page. */
        final HistoryStore.Entry entry;
        /** The time of the visit as shown on a page line ("" when unknown). */
        final String time;

        private Row(String header, HistoryStore.Entry entry, String time) {
            this.header = header;
            this.entry = entry;
            this.time = time;
        }

        boolean isHeader() { return header != null; }

        static Row heading(String text) { return new Row(text, null, ""); }

        static Row page(HistoryStore.Entry entry, String time) { return new Row(null, entry, time); }
    }

    private HistoryRows() { }

    static List<Row> build(List<HistoryStore.Entry> entries, Mode mode, long nowMs,
                           ZoneId zone, Locale locale) {
        final List<Row> rows = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return rows;

        final List<HistoryStore.Entry> sorted = new ArrayList<>();
        for (HistoryStore.Entry e : entries) {
            if (e != null && e.url != null) sorted.add(e);
        }
        Collections.sort(sorted, comparator(mode));

        if (mode == Mode.SITE) {
            buildBySite(sorted, zone, locale, rows);
        } else {
            buildByDay(sorted, nowMs, zone, locale, rows);
        }
        return rows;
    }

    private static Comparator<HistoryStore.Entry> comparator(final Mode mode) {
        final Comparator<HistoryStore.Entry> newest = new Comparator<HistoryStore.Entry>() {
            @Override public int compare(HistoryStore.Entry a, HistoryStore.Entry b) {
                final int c = Long.compare(b.lastVisited, a.lastVisited);
                return c != 0 ? c : a.url.compareTo(b.url);
            }
        };
        if (mode == Mode.OLDEST) {
            return new Comparator<HistoryStore.Entry>() {
                @Override public int compare(HistoryStore.Entry a, HistoryStore.Entry b) {
                    final int c = Long.compare(a.lastVisited, b.lastVisited);
                    return c != 0 ? c : a.url.compareTo(b.url);
                }
            };
        }
        if (mode == Mode.SITE) {
            return new Comparator<HistoryStore.Entry>() {
                @Override public int compare(HistoryStore.Entry a, HistoryStore.Entry b) {
                    final String sa = SuggestionRanker.hostOf(a.url);
                    final String sb = SuggestionRanker.hostOf(b.url);
                    if (sa.isEmpty() != sb.isEmpty()) return sa.isEmpty() ? 1 : -1;   // no site: last
                    final int c = sa.compareTo(sb);
                    return c != 0 ? c : newest.compare(a, b);
                }
            };
        }
        return newest;
    }

    private static void buildByDay(List<HistoryStore.Entry> sorted, long nowMs, ZoneId zone,
                                   Locale locale, List<Row> rows) {
        final DateTimeFormatter timeFmt =
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
        final DateTimeFormatter dayFmt =
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        final LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();

        boolean first = true;
        LocalDate day = null;
        for (HistoryStore.Entry e : sorted) {
            final boolean known = e.lastVisited > 0;
            final LocalDate d = known
                    ? Instant.ofEpochMilli(e.lastVisited).atZone(zone).toLocalDate() : null;
            if (first || !sameDay(day, d)) {
                rows.add(Row.heading(dayHeading(d, today, dayFmt)));
                day = d;
                first = false;
            }
            final String time = known
                    ? timeFmt.format(Instant.ofEpochMilli(e.lastVisited).atZone(zone)) : "";
            rows.add(Row.page(e, time));
        }
    }

    private static void buildBySite(List<HistoryStore.Entry> sorted, ZoneId zone, Locale locale,
                                    List<Row> rows) {
        final DateTimeFormatter fmt =
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale);
        int i = 0;
        while (i < sorted.size()) {
            final String site = SuggestionRanker.hostOf(sorted.get(i).url);
            int j = i;
            while (j < sorted.size() && SuggestionRanker.hostOf(sorted.get(j).url).equals(site)) j++;
            final int count = j - i;
            rows.add(Row.heading((site.isEmpty() ? "Other" : site) + "  \u00B7  " + count));
            for (int k = i; k < j; k++) {
                final HistoryStore.Entry e = sorted.get(k);
                final String time = e.lastVisited > 0
                        ? fmt.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.lastVisited), zone))
                        : "";
                rows.add(Row.page(e, time));
            }
            i = j;
        }
    }

    private static boolean sameDay(LocalDate a, LocalDate b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String dayHeading(LocalDate d, LocalDate today, DateTimeFormatter dayFmt) {
        if (d == null) return "Earlier";
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return dayFmt.format(d);
    }
}
