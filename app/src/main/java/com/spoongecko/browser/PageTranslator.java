package com.spoongecko.browser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.TranslationsController;
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation;
import org.mozilla.geckoview.TranslationsController.SessionTranslation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * [TRANSLATE] Translating web pages, on the device, with GeckoView's translation engine.
 *
 *  - A banner offers to translate a page that is in another language, shows the progress, and then
 *    offers to go back to the original.
 *  - "Translate..." in the menu opens a dialog to pick the languages by hand, set the language
 *    that pages are translated into, and manage the rules (always / never translate a language,
 *    never translate a site).
 *  - Pages in a language set to "always translate" are translated as soon as they load.
 *
 * Translation runs entirely on the phone. The first translation between two languages downloads
 * that pair's language data (a few tens of MB) from Mozilla; it is kept after that. Nothing about
 * the page is sent anywhere.
 *
 * GeckoView reports the page's language, an offer and the progress through a delegate that may be
 * called on any thread; every report is moved to the UI thread first. What to show for the
 * combination of reports is decided in TranslationFlow, which does not depend on their order.
 */
final class PageTranslator {

    private static final String TAG = "SpoonGecko";
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_TARGET = "translate_target";
    private static final String PREF_OFFERS = "translate_offers";
    /** A translation that shows no sign of finishing after this long is reported as a problem. */
    private static final long TIMEOUT_MS = 120_000L;

    private static final PageTranslator INSTANCE = new PageTranslator();

    static PageTranslator get() { return INSTANCE; }

    private PageTranslator() { }

    /**
     * Everything known about the page of one tab. It must NOT hold the session: it is the value
     * of a weak map keyed by the session, so a closed tab can be freed.
     */
    private static final class Page {
        final TranslationFlow.Facts facts = new TranslationFlow.Facts();
        boolean autoTried;
        /** The question "is this site set to never translate?" was asked. */
        boolean neverRequested;
        /** ...and it was answered. Translating by itself waits for this. */
        boolean neverKnown;
        long downloadBytes;
        int generation;
        String lastFrom;
        String lastTo;
        Runnable timeout;
    }

    /** What the translate dialog needs to know about the page on screen. */
    static final class PageInfo {
        final String docLang;
        final boolean docSupported;
        final boolean translated;
        final boolean translating;
        final String from;
        final String to;
        final boolean neverSite;

        PageInfo(String docLang, boolean docSupported, boolean translated, boolean translating,
                 String from, String to, boolean neverSite) {
            this.docLang = docLang;
            this.docSupported = docSupported;
            this.translated = translated;
            this.translating = translating;
            this.from = from;
            this.to = to;
            this.neverSite = neverSite;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<GeckoSession, Page> pages = new WeakHashMap<>();

    // Bound to the activity while it exists.
    private Activity activity;
    private TabManager tabs;
    private SharedPreferences prefs;
    private TranslateDialogs dialogs;
    private FrameLayout bannerParent;
    private View banner;
    private TextView bannerMessage;
    private ProgressBar bannerProgress;
    private TextView bannerPrimary;
    private TextView bannerSecondary;
    private boolean fullScreen;

    // What the engine knows. Loaded once, on first need, and kept (it does not depend on the activity).
    private Boolean engineSupported;
    private List<String> preferred;
    private List<TranslationLanguages.Lang> fromLangs;
    private List<TranslationLanguages.Lang> toLangs;
    private Map<String, String> rules;
    private boolean supportLoading;
    private boolean preferredLoading;
    private boolean languagesLoading;
    private boolean rulesLoading;
    private final List<Runnable> languageWaiters = new ArrayList<>();
    private final List<Runnable> rulesWaiters = new ArrayList<>();

    /** One delegate serves every session: each callback says which session it is about. */
    private final SessionTranslation.Delegate delegate = new SessionTranslation.Delegate() {
        @Override public void onOfferTranslate(final GeckoSession session) {
            main.post(() -> offered(session));
        }

        @Override public void onExpectedTranslate(final GeckoSession session) {
            main.post(() -> maybeAutoTranslate(session));
        }

        @Override public void onTranslationStateChange(final GeckoSession session,
                                                       final SessionTranslation.TranslationState state) {
            main.post(() -> stateChanged(session, state));
        }
    };

    // ------------------------------------------------------------------
    // Wiring (called from MainActivity)
    // ------------------------------------------------------------------

    /** Called once per activity, before any tab is attached. */
    void bind(Activity activity, FrameLayout parent, TabManager tabManager) {
        unbind();
        this.activity = activity;
        this.tabs = tabManager;
        this.bannerParent = parent;
        this.prefs = activity.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.dialogs = new TranslateDialogs(activity, this);
        inflateBanner(parent);
    }

    void unbind() {
        for (Page p : new ArrayList<>(pages.values())) cancelTimeout(p);
        if (dialogs != null) dialogs.dismissAll();
        if (banner != null && bannerParent != null) bannerParent.removeView(banner);
        banner = null;
        bannerParent = null;
        bannerMessage = null;
        bannerProgress = null;
        bannerPrimary = null;
        bannerSecondary = null;
        dialogs = null;
        activity = null;
        tabs = null;
        prefs = null;
        fullScreen = false;
    }

    /** Called for every tab's session, once, before it is opened. */
    void attach(Tab tab) {
        if (tab == null) return;
        try {
            tab.session.setTranslationsSessionDelegate(delegate);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not attach the translation delegate", e);
        }
        ensureEngineInfo();
    }

    /** A page started loading in this tab: whatever was known about the previous one is void. */
    void onPageStarted(Tab tab) {
        if (tab == null) return;
        final Page old = pages.remove(tab.session);
        if (old != null) cancelTimeout(old);
        if (isActive(tab.session)) refreshBanner();
    }

    void onActiveTabChanged(Tab tab) {
        refreshBanner();
    }

    void setFullScreen(boolean on) {
        fullScreen = on;
        refreshBanner();
    }

    /**
     * Lets the engine tell the app when a page could be translated (the offer that raises the
     * banner). That is its default; it is set explicitly so a profile that once changed it cannot
     * silently switch the banner off. Whether the banner is shown is the user's own switch
     * ({@link #offersEnabled()}), not this.
     */
    static void enableOffers(GeckoRuntime runtime) {
        if (runtime == null) return;
        try {
            runtime.getSettings().setTranslationsOfferPopup(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not enable translation offers", e);
        }
    }

    /** The menu entry, or null when this device cannot translate. */
    String menuLabel() {
        if (activity == null || Boolean.FALSE.equals(engineSupported)) return null;
        return "Translate\u2026";
    }

    void onMenuAction() {
        if (dialogs == null) return;
        ensureEngineInfo();
        dialogs.showTranslate();
    }

    // ------------------------------------------------------------------
    // What the engine reports
    // ------------------------------------------------------------------

    private Page page(GeckoSession session) {
        Page p = pages.get(session);
        if (p == null) {
            p = new Page();
            pages.put(session, p);
        }
        return p;
    }

    private void offered(GeckoSession session) {
        final Page p = page(session);
        p.facts.offered = true;
        refreshFor(session, p);
    }

    private void stateChanged(GeckoSession session, SessionTranslation.TranslationState state) {
        final Page p = page(session);
        final TranslationFlow.Facts f = p.facts;
        if (state == null) {
            f.pairFrom = null;
            f.pairTo = null;
            f.visibleChange = false;
            f.engineError = null;
        } else {
            final SessionTranslation.DetectedLanguages detected = state.detectedLanguages;
            if (detected != null) {
                f.docLang = detected.docLangTag;
                f.docSupported = detected.isDocLangTagSupported != null
                        && detected.isDocLangTagSupported.booleanValue();
            }
            final SessionTranslation.TranslationPair pair = state.requestedTranslationPair;
            f.pairFrom = pair == null ? null : pair.fromLanguage;
            f.pairTo = pair == null ? null : pair.toLanguage;
            f.visibleChange = state.hasVisibleChange != null && state.hasVisibleChange.booleanValue();
            f.engineError = state.error;
        }
        if (f.pairFrom != null && f.pairTo != null) {
            // GeckoView now reports the translation itself: our own "asked for" marker is done.
            f.pendingFrom = null;
            f.pendingTo = null;
            f.translationRequested = true;
            // It finished after all (slowly): a "taking too long" message no longer applies.
            if (f.visibleChange) f.failure = null;
        }
        if (f.docLang != null) {
            ensureNeverSite(session, p);
            maybeAutoTranslate(session);
        }
        refreshFor(session, p);
    }

    /** Asks whether the user said never to translate this site (needed before translating by itself). */
    private void ensureNeverSite(final GeckoSession session, final Page p) {
        if (p.neverRequested) return;
        final SessionTranslation st = session.getSessionTranslation();
        if (st == null) return;
        p.neverRequested = true;
        try {
            st.getNeverTranslateSiteSetting()
                    .accept(never -> main.post(() -> {
                        if (pages.get(session) != p) return;       // another page by now
                        p.facts.neverSite = Boolean.TRUE.equals(never);
                        p.neverKnown = true;
                        maybeAutoTranslate(session);
                        refreshFor(session, p);
                    }))
                    .exceptionally(e -> {
                        Log.w(TAG, "could not read the never-translate setting of the site", e);
                        return null;
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the never-translate setting of the site", e);
        }
    }

    /** Translates by itself when the page's language is set to "always translate". */
    private void maybeAutoTranslate(final GeckoSession session) {
        final Page p = pages.get(session);
        if (p == null) return;
        final TranslationFlow.Facts f = p.facts;
        if (f.docLang == null || !f.docSupported) return;
        if (rules == null) {
            whenRulesLoaded(() -> maybeAutoTranslate(session));
            return;
        }
        if (!p.neverKnown) {
            // Never translate by itself before knowing whether the user blocked this site. If the
            // answer cannot be read, nothing is translated by itself (the banner and the menu
            // still work).
            ensureNeverSite(session, p);       // calls this method again once the answer is in
            return;
        }
        f.target = targetLanguage();
        final boolean always = RuntimeTranslation.ALWAYS.equals(ruleFor(f.docLang));
        if (!TranslationFlow.shouldAutoTranslate(f, always, p.autoTried)) return;
        p.autoTried = true;
        startTranslation(session, p, f.docLang, f.target);
    }

    // ------------------------------------------------------------------
    // Doing things
    // ------------------------------------------------------------------

    /** Asks GeckoView to translate the page. Download of the language data is automatic. */
    private void startTranslation(final GeckoSession session, final Page p,
                                  String fromTag, String toTag) {
        final String from = engineCode(fromTag, fromLangs);
        final String to = engineCode(toTag, toLangs);
        if (from == null || to == null || TranslationLanguages.sameLanguage(from, to)) {
            toast("Choose two different languages");
            return;
        }
        final SessionTranslation st = session.getSessionTranslation();
        final TranslationFlow.Facts f = p.facts;
        p.generation++;
        final int gen = p.generation;
        cancelTimeout(p);
        f.dismissed = false;
        f.failure = null;
        f.engineError = null;
        f.translationRequested = true;
        f.pendingFrom = from;
        f.pendingTo = to;
        p.lastFrom = from;
        p.lastTo = to;
        p.downloadBytes = 0;
        if (st == null) {
            translateFailed(session, p, gen, null);
            return;
        }
        refreshFor(session, p);
        // How much has to be downloaded first, if anything: shown while translating.
        try {
            RuntimeTranslation.checkPairDownloadSize(from, to)
                    .accept(bytes -> main.post(() -> {
                        if (pages.get(session) != p || p.generation != gen) return;
                        p.downloadBytes = bytes == null ? 0 : bytes.longValue();
                        refreshFor(session, p);
                    }))
                    .exceptionally(e -> null);     // only informational
        } catch (RuntimeException e) {
            Log.w(TAG, "could not check the language download size", e);
        }
        try {
            st.translate(from, to,
                    new SessionTranslation.TranslationOptions.Builder().downloadModel(true).build())
                    .exceptionally(e -> {
                        main.post(() -> translateFailed(session, p, gen, e));
                        return null;
                    });
        } catch (RuntimeException e) {
            translateFailed(session, p, gen, e);
        }
    }

    private void translateFailed(GeckoSession session, Page p, int gen, Throwable error) {
        if (pages.get(session) != p || p.generation != gen) return;     // overtaken by a newer request
        Log.w(TAG, "translation failed", error);
        final TranslationFlow.Facts f = p.facts;
        f.pendingFrom = null;
        f.pendingTo = null;
        f.failure = describe(error);
        cancelTimeout(p);
        refreshFor(session, p);
    }

    private static String describe(Throwable error) {
        if (error instanceof TranslationsController.TranslationsException) {
            switch (((TranslationsController.TranslationsException) error).code) {
                case TranslationsController.TranslationsException.ERROR_ENGINE_NOT_SUPPORTED:
                    return "Translation isn't supported on this device";
                case TranslationsController.TranslationsException.ERROR_LANGUAGE_NOT_SUPPORTED:
                    return "This language can't be translated yet";
                case TranslationsController.TranslationsException.ERROR_MODEL_COULD_NOT_DOWNLOAD:
                    return "Couldn't download the language data. Check your connection and try again.";
                case TranslationsController.TranslationsException.ERROR_MODEL_DOWNLOAD_REQUIRED:
                    return "The language data has to be downloaded first";
                case TranslationsController.TranslationsException.ERROR_COULD_NOT_TRANSLATE:
                    return TranslationFlow.GENERIC_ERROR;
                default:
                    break;
            }
        }
        return TranslationFlow.GENERIC_ERROR;
    }

    /** Back to the original text. {@code then} runs afterwards (a new translation); may be null. */
    private void restore(final GeckoSession session, final Page p, final boolean closeBanner,
                         final Runnable then) {
        final SessionTranslation st = session.getSessionTranslation();
        p.generation++;
        final int gen = p.generation;
        cancelTimeout(p);
        if (st == null) {
            toast("Couldn't show the original page");
            return;
        }
        try {
            st.restoreOriginalPage()
                    .accept(v -> main.post(() -> {
                        if (pages.get(session) != p || p.generation != gen) return;
                        final TranslationFlow.Facts f = p.facts;
                        f.pairFrom = null;
                        f.pairTo = null;
                        f.visibleChange = false;
                        f.engineError = null;
                        f.pendingFrom = null;
                        f.pendingTo = null;
                        f.failure = null;
                        f.translationRequested = false;
                        if (closeBanner) f.dismissed = true;
                        refreshFor(session, p);
                        if (then != null) then.run();
                    }))
                    .exceptionally(e -> {
                        main.post(() -> {
                            if (pages.get(session) != p) return;
                            Log.w(TAG, "could not restore the original page", e);
                            p.facts.pendingFrom = null;
                            p.facts.pendingTo = null;
                            p.facts.failure = "Couldn't show the original page";
                            refreshFor(session, p);
                        });
                        return null;
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "could not restore the original page", e);
            toast("Couldn't show the original page");
        }
    }

    // ------------------------------------------------------------------
    // Banner
    // ------------------------------------------------------------------

    private void inflateBanner(FrameLayout parent) {
        banner = LayoutInflater.from(activity).inflate(R.layout.view_translate_banner, parent, false);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        final int m = dp(10);
        lp.setMargins(m, m, m, m);
        banner.setLayoutParams(lp);
        banner.setElevation(dp(6));
        banner.setVisibility(View.GONE);
        bannerMessage = banner.findViewById(R.id.tb_message);
        bannerProgress = banner.findViewById(R.id.tb_progress);
        bannerPrimary = banner.findViewById(R.id.tb_primary);
        bannerSecondary = banner.findViewById(R.id.tb_secondary);
        banner.findViewById(R.id.tb_close).setOnClickListener(v -> closeBanner());
        bannerSecondary.setOnClickListener(v -> onMenuAction());
        bannerPrimary.setOnClickListener(v -> onPrimaryClicked());
        parent.addView(banner);
    }

    private Tab activeTab() {
        return tabs == null ? null : tabs.getActiveTab();
    }

    private boolean isActive(GeckoSession session) {
        final Tab t = activeTab();
        return t != null && t.session == session;
    }

    private void refreshBanner() {
        final Tab t = activeTab();
        if (t == null || banner == null) return;
        final Page p = pages.get(t.session);
        if (p == null) {
            banner.setVisibility(View.GONE);
            return;
        }
        refreshFor(t.session, p);
    }

    /** Re-decides what to show for one page, arms or disarms the watchdog, and draws the banner. */
    private void refreshFor(GeckoSession session, Page p) {
        final TranslationFlow.Ui ui = decide(p);
        if (ui.kind == TranslationFlow.Kind.TRANSLATING) {
            if (p.timeout == null) armTimeout(session, p);
        } else {
            cancelTimeout(p);
        }
        if (isActive(session)) renderBanner(p, ui);
    }

    private TranslationFlow.Ui decide(Page p) {
        final TranslationFlow.Facts f = p.facts;
        f.target = targetLanguage();
        f.offersEnabled = offersEnabled();
        f.engineSupported = !Boolean.FALSE.equals(engineSupported);
        return TranslationFlow.decide(f);
    }

    private void renderBanner(Page p, TranslationFlow.Ui ui) {
        if (banner == null) return;
        final Tab t = activeTab();
        if (ui.kind == TranslationFlow.Kind.HIDDEN || fullScreen || t == null
                || !isWebPage(t) || t.awaitingOpen) {
            banner.setVisibility(View.GONE);
            return;
        }
        bannerMessage.setText(TranslationFlow.text(ui, languageName(ui.from), languageName(ui.to),
                p.downloadBytes));
        bannerProgress.setVisibility(
                ui.kind == TranslationFlow.Kind.TRANSLATING ? View.VISIBLE : View.GONE);
        bannerSecondary.setVisibility(ui.kind == TranslationFlow.Kind.TRANSLATING
                ? View.GONE : View.VISIBLE);
        switch (ui.kind) {
            case OFFER:
                bannerPrimary.setText("Translate");
                break;
            case TRANSLATING:
                bannerPrimary.setText("Cancel");
                break;
            case TRANSLATED:
                bannerPrimary.setText("Show original");
                break;
            default:
                bannerPrimary.setText("Try again");
                break;
        }
        banner.setVisibility(View.VISIBLE);
    }

    /** The main button of the banner; what it does depends on what the banner shows right now. */
    private void onPrimaryClicked() {
        final Tab t = activeTab();
        if (t == null) return;
        final Page p = pages.get(t.session);
        if (p == null) return;
        final TranslationFlow.Ui ui = decide(p);
        switch (ui.kind) {
            case OFFER:
                startTranslation(t.session, p, ui.from, ui.to);
                break;
            case TRANSLATING:
            case TRANSLATED:
                restore(t.session, p, true, null);
                break;
            case ERROR:
                if (p.lastFrom != null && p.lastTo != null) {
                    startTranslation(t.session, p, p.lastFrom, p.lastTo);
                } else {
                    p.facts.failure = null;
                    refreshFor(t.session, p);
                }
                break;
            default:
                break;
        }
    }

    private void closeBanner() {
        final Tab t = activeTab();
        if (t == null) return;
        final Page p = pages.get(t.session);
        if (p == null) return;
        p.facts.dismissed = true;
        refreshFor(t.session, p);
    }

    // ------------------------------------------------------------------
    // Watchdog: a translation that never finishes
    // ------------------------------------------------------------------

    private void armTimeout(GeckoSession session, final Page p) {
        final WeakReference<GeckoSession> ref = new WeakReference<>(session);
        final int gen = p.generation;
        p.timeout = () -> {
            p.timeout = null;
            final GeckoSession s = ref.get();
            if (s == null || pages.get(s) != p || p.generation != gen) return;
            if (decide(p).kind != TranslationFlow.Kind.TRANSLATING) return;
            p.facts.pendingFrom = null;
            p.facts.pendingTo = null;
            p.facts.failure = "The translation is taking longer than expected";
            refreshFor(s, p);
        };
        main.postDelayed(p.timeout, TIMEOUT_MS);
    }

    private void cancelTimeout(Page p) {
        if (p.timeout != null) {
            main.removeCallbacks(p.timeout);
            p.timeout = null;
        }
    }

    // ------------------------------------------------------------------
    // Engine information (loaded on first need)
    // ------------------------------------------------------------------

    private void ensureEngineInfo() {
        if (engineSupported == null && !supportLoading) {
            supportLoading = true;
            try {
                RuntimeTranslation.isTranslationsEngineSupported()
                        .accept(ok -> main.post(() -> {
                            supportLoading = false;
                            engineSupported = ok;
                            refreshBanner();
                        }))
                        .exceptionally(e -> {
                            main.post(() -> supportLoading = false);
                            Log.w(TAG, "could not check whether translation is supported", e);
                            return null;
                        });
            } catch (RuntimeException e) {
                supportLoading = false;
                Log.w(TAG, "could not check whether translation is supported", e);
            }
        }
        if (preferred == null && !preferredLoading) {
            preferredLoading = true;
            try {
                RuntimeTranslation.preferredLanguages()
                        .accept(list -> main.post(() -> {
                            preferredLoading = false;
                            preferred = list == null ? new ArrayList<String>() : new ArrayList<>(list);
                            refreshBanner();
                        }))
                        .exceptionally(e -> {
                            main.post(() -> preferredLoading = false);
                            return null;
                        });
            } catch (RuntimeException e) {
                preferredLoading = false;
            }
        }
        if (rules == null && !rulesLoading) whenRulesLoaded(null);
    }

    /** Runs {@code ready} once the language rules are known (they are loaded if needed). */
    void whenRulesLoaded(Runnable ready) {
        if (rules != null) {
            if (ready != null) ready.run();
            return;
        }
        if (ready != null) rulesWaiters.add(ready);
        if (rulesLoading) return;
        rulesLoading = true;
        try {
            RuntimeTranslation.getLanguageSettings()
                    .accept(map -> main.post(() -> {
                        rulesLoading = false;
                        rules = map == null ? new HashMap<String, String>() : new HashMap<>(map);
                        final List<Runnable> waiting = new ArrayList<>(rulesWaiters);
                        rulesWaiters.clear();
                        for (Runnable r : waiting) r.run();
                    }))
                    .exceptionally(e -> {
                        main.post(this::rulesFailed);
                        Log.w(TAG, "could not read the language rules", e);
                        return null;
                    });
        } catch (RuntimeException e) {
            rulesFailed();
            Log.w(TAG, "could not read the language rules", e);
        }
    }

    private void rulesFailed() {
        rulesLoading = false;
        final boolean someoneWaits = !rulesWaiters.isEmpty();
        rulesWaiters.clear();
        if (someoneWaits) toast("Couldn't read the language rules. Try again in a moment.");
    }

    /**
     * Runs {@code ready} once the lists of languages the engine offers are known. If they cannot be
     * loaded (no connection to the engine yet) the user is told and {@code ready} does not run.
     */
    void whenLanguagesLoaded(Runnable ready) {
        if (fromLangs != null && toLangs != null) {
            ready.run();
            return;
        }
        languageWaiters.add(ready);
        if (languagesLoading) return;
        languagesLoading = true;
        try {
            RuntimeTranslation.listSupportedLanguages()
                    .accept(support -> main.post(() -> {
                        languagesLoading = false;
                        fromLangs = convert(support == null ? null : support.fromLanguages);
                        toLangs = convert(support == null ? null : support.toLanguages);
                        final List<Runnable> waiting = new ArrayList<>(languageWaiters);
                        languageWaiters.clear();
                        for (Runnable r : waiting) r.run();
                        refreshBanner();
                    }))
                    .exceptionally(e -> {
                        main.post(() -> languagesFailed());
                        Log.w(TAG, "could not load the list of languages", e);
                        return null;
                    });
        } catch (RuntimeException e) {
            languagesFailed();
            Log.w(TAG, "could not load the list of languages", e);
        }
    }

    private void languagesFailed() {
        languagesLoading = false;
        languageWaiters.clear();
        toast("Couldn't load the list of languages. Try again in a moment.");
    }

    private static List<TranslationLanguages.Lang> convert(List<TranslationsController.Language> in) {
        final List<TranslationLanguages.Lang> out = new ArrayList<>();
        if (in != null) {
            for (TranslationsController.Language l : in) {
                if (l != null && l.code != null) {
                    out.add(new TranslationLanguages.Lang(l.code,
                            TranslationLanguages.displayName(l.code, l.localizedDisplayName)));
                }
            }
        }
        return TranslationLanguages.sorted(out);
    }

    // ------------------------------------------------------------------
    // For the dialogs
    // ------------------------------------------------------------------

    List<TranslationLanguages.Lang> fromLanguages() {
        return fromLangs == null ? new ArrayList<TranslationLanguages.Lang>() : fromLangs;
    }

    List<TranslationLanguages.Lang> toLanguages() {
        return toLangs == null ? new ArrayList<TranslationLanguages.Lang>() : toLangs;
    }

    /** The name to show for a language code. */
    String languageName(String code) {
        if (code == null) return "";
        for (TranslationLanguages.Lang l : fromLanguages()) if (l.code.equals(code)) return l.name;
        for (TranslationLanguages.Lang l : toLanguages()) if (l.code.equals(code)) return l.name;
        return TranslationLanguages.displayName(code, null);
    }

    /** The engine's own spelling of a language tag, or the tag itself when the lists are not known. */
    private static String engineCode(String tag, List<TranslationLanguages.Lang> list) {
        if (tag == null || tag.trim().isEmpty()) return null;
        if (list == null || list.isEmpty()) return tag.trim();
        final List<String> codes = new ArrayList<>();
        for (TranslationLanguages.Lang l : list) codes.add(l.code);
        final String m = TranslationLanguages.match(tag, codes);
        return m == null ? tag.trim() : m;
    }

    /** The language pages are translated into. */
    String targetLanguage() {
        final String saved = prefs == null ? null : prefs.getString(PREF_TARGET, null);
        final List<String> codes = new ArrayList<>();
        for (TranslationLanguages.Lang l : toLanguages()) codes.add(l.code);
        return TranslationLanguages.pickTarget(saved, preferred, codes,
                Locale.getDefault().getLanguage());
    }

    void setTarget(String code) {
        if (prefs != null && code != null) prefs.edit().putString(PREF_TARGET, code).apply();
        refreshBanner();
    }

    boolean offersEnabled() {
        return prefs == null || prefs.getBoolean(PREF_OFFERS, true);
    }

    void setOffersEnabled(boolean on) {
        if (prefs != null) prefs.edit().putBoolean(PREF_OFFERS, on).apply();
        refreshBanner();
    }

    /** http(s) pages only: not the home page, an extension page or an internal one. */
    private static boolean isWebPage(Tab t) {
        if (t == null || t.url == null) return false;
        final String lower = t.url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** The page on screen, or null when there is none (home page, nothing loaded). */
    PageInfo currentPage() {
        final Tab t = activeTab();
        if (t == null || t.awaitingOpen || !isWebPage(t)) return null;
        final Page p = page(t.session);
        final TranslationFlow.Ui ui = decide(p);
        final TranslationFlow.Facts f = p.facts;
        final boolean translated = ui.kind == TranslationFlow.Kind.TRANSLATED;
        final boolean translating = ui.kind == TranslationFlow.Kind.TRANSLATING;
        return new PageInfo(f.docLang, f.docSupported, translated, translating,
                translated || translating ? ui.from : null,
                translated || translating ? ui.to : null, f.neverSite);
    }

    /** Translate (or translate again) the page on screen between the languages picked by hand. */
    void translateFromDialog(final String from, final String to) {
        final Tab t = activeTab();
        if (t == null || t.awaitingOpen) return;
        setTarget(to);
        final GeckoSession session = t.session;
        final Page p = page(session);
        final TranslationFlow.Kind kind = decide(p).kind;
        if (kind == TranslationFlow.Kind.TRANSLATED || kind == TranslationFlow.Kind.TRANSLATING) {
            // A different language pair: back to the original first, then translate again.
            restore(session, p, false, () -> startTranslation(session, p, from, to));
        } else {
            startTranslation(session, p, from, to);
        }
    }

    void restoreFromDialog() {
        final Tab t = activeTab();
        if (t == null) return;
        final Page p = pages.get(t.session);
        if (p != null) restore(t.session, p, true, null);
    }

    /** Sets "never translate this site" for the page on screen. */
    void setNeverSite(final boolean never) {
        final Tab t = activeTab();
        if (t == null) return;
        final GeckoSession session = t.session;
        final Page p = page(session);
        final SessionTranslation st = session.getSessionTranslation();
        if (st == null) {
            toast("Couldn't change the setting");
            return;
        }
        try {
            st.setNeverTranslateSiteSetting(Boolean.valueOf(never))
                    .accept(v -> main.post(() -> {
                        p.facts.neverSite = never;
                        p.neverRequested = true;
                        p.neverKnown = true;
                        refreshFor(session, p);
                    }))
                    .exceptionally(e -> {
                        main.post(() -> toast("Couldn't change the setting"));
                        return null;
                    });
        } catch (RuntimeException e) {
            toast("Couldn't change the setting");
        }
    }

    /** The rule for a language: RuntimeTranslation.ALWAYS, NEVER or OFFER (the default). */
    String ruleFor(String code) {
        final String key = TranslationLanguages.primaryOf(code);
        final String rule = rules == null ? null : rules.get(key);
        return rule == null || rule.isEmpty() ? RuntimeTranslation.OFFER : rule;
    }

    /** All languages that have a rule other than the default, as code to rule. */
    Map<String, String> customRules() {
        final Map<String, String> out = new HashMap<>();
        if (rules != null) {
            for (Map.Entry<String, String> e : rules.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()
                        && !RuntimeTranslation.OFFER.equals(e.getValue())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    /** Saves a rule for a language and runs {@code done} on success. */
    void setRule(final String code, final String rule, final Runnable done) {
        final String key = TranslationLanguages.primaryOf(code);
        if (key.isEmpty()) return;
        try {
            RuntimeTranslation.setLanguageSettings(key, rule)
                    .accept(v -> main.post(() -> {
                        if (rules == null) rules = new HashMap<>();
                        rules.put(key, rule);
                        if (done != null) done.run();
                        // "Always translate" applies at once to the page that is open in that language.
                        final Tab t = activeTab();
                        if (t != null && RuntimeTranslation.ALWAYS.equals(rule)) {
                            final Page p = pages.get(t.session);
                            if (p != null && TranslationLanguages.sameLanguage(p.facts.docLang, key)) {
                                p.autoTried = false;
                                maybeAutoTranslate(t.session);
                            }
                        }
                        refreshBanner();
                    }))
                    .exceptionally(e -> {
                        main.post(() -> toast("Couldn't save the setting"));
                        Log.w(TAG, "could not save the language rule", e);
                        return null;
                    });
        } catch (RuntimeException e) {
            toast("Couldn't save the setting");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    void toast(String message) {
        if (activity != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
