package com.spoongecko.browser;

import java.util.Locale;

/**
 * [TRANSLATE] Decides what the translation banner shows, from what is known about the page.
 * Pure Java (no Android or GeckoView classes) so every combination can be tested.
 *
 * The facts come from two places that report independently and in no fixed order: GeckoView
 * (the language it detected, whether it offers a translation, the translation it is running) and
 * the app itself (what the user asked for, what failed, whether the banner was closed). Keeping the
 * decision in one function of ALL the facts is what makes it robust: it does not matter which
 * report arrives first.
 */
final class TranslationFlow {

    private TranslationFlow() { }

    enum Kind { HIDDEN, OFFER, TRANSLATING, TRANSLATED, ERROR }

    /** What is known about the page on screen. */
    static final class Facts {
        // --- the app -----------------------------------------------------
        boolean engineSupported = true;
        /** The user's own on/off switch for translation offers. */
        boolean offersEnabled = true;
        /** The user said never to translate this site. */
        boolean neverSite;
        /** The language the user translates to. */
        String target;
        /** The user closed the banner on this page (or went back to the original). */
        boolean dismissed;
        /** A translation was asked for on this page (by the user, or automatically). */
        boolean translationRequested;
        /** Asked for, but GeckoView has not reported the translation yet. */
        String pendingFrom;
        String pendingTo;
        /** A failure the app itself noticed (the request failed, or it took too long). */
        String failure;

        // --- GeckoView ---------------------------------------------------
        /** GeckoView says this page is worth offering a translation for. */
        boolean offered;
        /** The language it detected for the page, and whether the engine can translate it. */
        String docLang;
        boolean docSupported;
        /** The translation GeckoView is running or has done. */
        String pairFrom;
        String pairTo;
        /** The page has started to visibly change to the translated text. */
        boolean visibleChange;
        /** An error GeckoView reports for the translation. */
        String engineError;
    }

    /** What to show. */
    static final class Ui {
        final Kind kind;
        final String from;
        final String to;
        final String error;

        private Ui(Kind kind, String from, String to, String error) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.error = error;
        }

        static final Ui HIDDEN = new Ui(Kind.HIDDEN, null, null, null);
    }

    static final String GENERIC_ERROR = "The page couldn't be translated";

    static Ui decide(Facts f) {
        if (!f.engineSupported) return Ui.HIDDEN;
        if (f.dismissed) return Ui.HIDDEN;
        final boolean hasPair = f.pairFrom != null && f.pairTo != null;

        if (f.failure != null && !f.failure.isEmpty()) {
            return new Ui(Kind.ERROR, null, null, f.failure);
        }
        // An error GeckoView reports only counts once a translation was actually asked for:
        // before that it can describe the state of the page, not a failure.
        if (f.engineError != null && !f.engineError.isEmpty()
                && (f.translationRequested || hasPair)) {
            return new Ui(Kind.ERROR, null, null, GENERIC_ERROR);
        }
        if (hasPair) {
            return new Ui(f.visibleChange ? Kind.TRANSLATED : Kind.TRANSLATING,
                    f.pairFrom, f.pairTo, null);
        }
        if (f.pendingFrom != null && f.pendingTo != null) {
            return new Ui(Kind.TRANSLATING, f.pendingFrom, f.pendingTo, null);
        }
        if (f.offered && f.offersEnabled && !f.neverSite
                && f.docLang != null && f.docSupported
                && f.target != null && !TranslationLanguages.sameLanguage(f.docLang, f.target)) {
            return new Ui(Kind.OFFER, f.docLang, f.target, null);
        }
        return Ui.HIDDEN;
    }

    /**
     * Whether the page should be translated without asking: the user said "always translate" for
     * its language, and nothing has been tried or chosen on this page yet.
     */
    static boolean shouldAutoTranslate(Facts f, boolean ruleIsAlways, boolean alreadyTried) {
        return f.engineSupported && ruleIsAlways && !alreadyTried
                && !f.neverSite && !f.dismissed
                && f.docLang != null && f.docSupported
                && f.target != null && !TranslationLanguages.sameLanguage(f.docLang, f.target)
                && f.pairFrom == null && f.pendingFrom == null;
    }

    /** The sentence shown in the banner. */
    static String text(Ui ui, String fromName, String toName, long downloadBytes) {
        switch (ui.kind) {
            case OFFER:
                return "Translate this page from " + fromName + " to " + toName + "?";
            case TRANSLATING:
                final String base = "Translating from " + fromName + " to " + toName + "\u2026";
                return downloadBytes > 0
                        ? base + "\nDownloading language data (" + formatSize(downloadBytes) + ")"
                        : base;
            case TRANSLATED:
                return "Translated from " + fromName + " to " + toName;
            case ERROR:
                return ui.error == null || ui.error.isEmpty() ? GENERIC_ERROR : ui.error;
            default:
                return "";
        }
    }

    /** "850 KB", "4.2 MB", "38 MB". */
    static String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) {
            return Math.max(1, Math.round(bytes / 1024f)) + " KB";
        }
        final double mb = bytes / (1024.0 * 1024.0);
        return mb < 10 ? String.format(Locale.ROOT, "%.1f MB", mb)
                : Math.round(mb) + " MB";
    }
}
