package com.spoongecko.browser;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [TRANSLATE] Language handling for page translation. Pure Java (no Android classes) so it can be
 * tested on its own.
 *
 * Language tags are BCP 47 ("es", "pt-BR", "zh-Hant"). The translation engine supports a fixed list
 * of codes, and a page or the phone can name a language more specifically ("pt-BR") or in one of
 * Android's older spellings ("iw" for Hebrew), so a tag has to be matched to the engine's code
 * before it is used.
 */
final class TranslationLanguages {

    private TranslationLanguages() { }

    /** One language as shown in a list. */
    static final class Lang {
        final String code;
        final String name;

        Lang(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    /**
     * The primary language of a tag, lower case: "pt-BR" gives "pt". Android's legacy codes are
     * mapped to the current ones ("iw" to "he", "in" to "id", "ji" to "yi") and "no" to "nb".
     * The engine's own language settings are keyed by this part.
     */
    static String primaryOf(String tag) {
        if (tag == null) return "";
        final String t = tag.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        final int dash = t.indexOf('-');
        return fixLegacy(dash < 0 ? t : t.substring(0, dash));
    }

    private static String fixLegacy(String primary) {
        switch (primary) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            case "no": return "nb";
            default: return primary;
        }
    }

    /** True when both tags are the same language, ignoring region and script ("pt" and "pt-BR"). */
    static boolean sameLanguage(String a, String b) {
        final String pa = primaryOf(a);
        return !pa.isEmpty() && pa.equals(primaryOf(b));
    }

    /**
     * The supported code that best fits a tag: an exact match, otherwise the tag with its trailing
     * parts removed one at a time ("zh-Hant-TW", then "zh-Hant", then "zh"). Null when nothing fits.
     */
    static String match(String tag, Collection<String> supported) {
        if (tag == null || supported == null || supported.isEmpty()) return null;
        final String clean = tag.trim().replace('_', '-');
        if (clean.isEmpty()) return null;
        final Map<String, String> byLower = new HashMap<>();
        for (String s : supported) {
            if (s != null) byLower.put(s.toLowerCase(Locale.ROOT), s);
        }
        final int firstDash = clean.indexOf('-');
        final String head = fixLegacy((firstDash < 0 ? clean : clean.substring(0, firstDash))
                .toLowerCase(Locale.ROOT));
        String cur = firstDash < 0 ? head
                : head + clean.substring(firstDash).toLowerCase(Locale.ROOT);
        while (true) {
            final String hit = byLower.get(cur);
            if (hit != null) return hit;
            final int dash = cur.lastIndexOf('-');
            if (dash < 0) return null;
            cur = cur.substring(0, dash);
        }
    }

    /**
     * The language to translate TO: the one the user chose, otherwise the phone's languages in
     * order, otherwise English, otherwise the first one the engine offers.
     *
     * @param saved       the user's own choice, or null
     * @param preferred   the languages the engine reports as the user's, best first (may be null)
     * @param supportedTo codes the engine can translate to; null or empty when not known yet
     * @param deviceLanguage the phone's language, from Locale
     */
    static String pickTarget(String saved, List<String> preferred, Collection<String> supportedTo,
                             String deviceLanguage) {
        if (supportedTo == null || supportedTo.isEmpty()) {
            if (saved != null && !saved.trim().isEmpty()) return saved.trim();
            final String d = primaryOf(deviceLanguage);
            return d.isEmpty() ? "en" : d;
        }
        String m = match(saved, supportedTo);
        if (m != null) return m;
        if (preferred != null) {
            for (String p : preferred) {
                m = match(p, supportedTo);
                if (m != null) return m;
            }
        }
        m = match(deviceLanguage, supportedTo);
        if (m != null) return m;
        m = match("en", supportedTo);
        if (m != null) return m;
        return supportedTo.iterator().next();
    }

    /** The name to show for a language: the engine's own, else the platform's, else the code. */
    static String displayName(String code, String localizedName) {
        if (localizedName != null && !localizedName.trim().isEmpty()) return localizedName.trim();
        if (code == null || code.isEmpty()) return "";
        try {
            final String n = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault());
            if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(code)) {
                return n.substring(0, 1).toUpperCase(Locale.getDefault()) + n.substring(1);
            }
        } catch (RuntimeException ignored) {
            // fall back to the code
        }
        return code;
    }

    /** Alphabetical by name for the current locale; each code once. */
    static List<Lang> sorted(Collection<Lang> in) {
        final Map<String, Lang> unique = new LinkedHashMap<>();
        if (in != null) {
            for (Lang l : in) {
                if (l != null && l.code != null && !unique.containsKey(l.code)) unique.put(l.code, l);
            }
        }
        final List<Lang> out = new ArrayList<>(unique.values());
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(out, new Comparator<Lang>() {
            @Override public int compare(Lang a, Lang b) {
                final int c = collator.compare(a.name, b.name);
                return c != 0 ? c : a.code.compareTo(b.code);
            }
        });
        return out;
    }

    /** The languages whose name or code contains the text (case-insensitive); all when it is blank. */
    static List<Lang> filter(List<Lang> in, String query) {
        if (in == null) return new ArrayList<>();
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) return new ArrayList<>(in);
        final List<Lang> out = new ArrayList<>();
        for (Lang l : in) {
            if (l.name.toLowerCase(Locale.getDefault()).contains(q)
                    || l.code.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(l);
            }
        }
        return out;
    }
}
