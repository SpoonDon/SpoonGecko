package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.mozilla.geckoview.TranslationsController.RuntimeTranslation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * [TRANSLATE] The dialogs of page translation, in the same glass style as the rest of the app:
 *  - the translate dialog (languages, the action, the rules for this page and for offers),
 *  - a searchable list of languages,
 *  - the list of language rules ("always" / "never" translate), and a chooser for one rule.
 */
final class TranslateDialogs {

    private static final int WRAP = WindowManager.LayoutParams.WRAP_CONTENT;
    private static final int LIST_MAX_DP = 260;
    /** The list of rules never gets shorter than this, however small the window becomes. */
    private static final int LIST_MIN_DP = 96;
    /** Everything in the rules dialog except its list (title, text, buttons, padding), with room to spare. */
    private static final int RULES_CHROME_DP = 200;

    private final Activity activity;
    private final PageTranslator translator;
    private final List<Dialog> open = new ArrayList<>();

    TranslateDialogs(Activity activity, PageTranslator translator) {
        this.activity = activity;
        this.translator = translator;
    }

    /** Closes everything that is showing (the activity is going away). */
    void dismissAll() {
        for (Dialog d : new ArrayList<>(open)) {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        open.clear();
    }

    private boolean finishing() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    /**
     * @param widthFraction  the dialog's width as a fraction of the app window's width
     * @param heightFraction its height as a fraction of the app window's height, or 0 to let the
     *                       content decide. Both are worked out again whenever the app window is
     *                       resized (split screen, freeform window, rotation) while the dialog is
     *                       showing; see DialogFit.
     */
    private Dialog newDialog(int layout, float widthFraction, float heightFraction) {
        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(layout);
        final Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        DialogFit.fitAndFollow(activity, d, widthFraction, heightFraction, null);
        open.add(d);
        d.setOnDismissListener(x -> open.remove(d));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /** Selected chips are filled and bright; the others are quiet. */
    private static void setChip(TextView v, boolean selected) {
        v.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        v.setTextColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
    }

    private static List<String> codes(List<TranslationLanguages.Lang> list) {
        final List<String> out = new ArrayList<>();
        for (TranslationLanguages.Lang l : list) out.add(l.code);
        return out;
    }

    // ------------------------------------------------------------------
    // The translate dialog
    // ------------------------------------------------------------------

    /** Opens the translate dialog once the lists of languages are known. */
    void showTranslate() {
        if (finishing()) return;
        translator.whenLanguagesLoaded(this::buildTranslate);
    }

    /** Opens the default target language picker directly (used from Settings). */
    void showTargetLanguagePicker(final Runnable onPicked) {
        if (finishing()) return;
        translator.whenLanguagesLoaded(() -> {
            if (finishing()) return;
            final String current = TranslationLanguages.match(
                    translator.targetLanguage(), codes(translator.toLanguages()));
            pickLanguage("Default target language", translator.toLanguages(), current, lang -> {
                translator.setTarget(lang.code);
                if (onPicked != null) onPicked.run();
            });
        });
    }

    private void buildTranslate() {
        if (finishing()) return;
        final PageTranslator.PageInfo page = translator.currentPage();
        final List<String> fromCodes = codes(translator.fromLanguages());
        final List<String> toCodes = codes(translator.toLanguages());
        final Dialog d = newDialog(R.layout.dialog_translate, 0.92f, 0f);

        final TextView status = d.findViewById(R.id.tr_status);
        final View pageGroup = d.findViewById(R.id.tr_page_group);
        final TextView fromView = d.findViewById(R.id.tr_from);
        final TextView toView = d.findViewById(R.id.tr_to);
        final TextView action = d.findViewById(R.id.tr_action);
        final TextView ruleLabel = d.findViewById(R.id.tr_rule_label);
        final View ruleRow = d.findViewById(R.id.tr_rule_row);
        final TextView ruleAsk = d.findViewById(R.id.tr_rule_ask);
        final TextView ruleAlways = d.findViewById(R.id.tr_rule_always);
        final TextView ruleNever = d.findViewById(R.id.tr_rule_never);
        final View siteRow = d.findViewById(R.id.tr_site_row);
        final TextView siteToggle = d.findViewById(R.id.tr_site_toggle);
        final TextView offersToggle = d.findViewById(R.id.tr_offers_toggle);
        final TextView targetRow = d.findViewById(R.id.tr_target_row);

        // What is selected now. A page that is being translated starts with its own languages.
        final boolean active = page != null && (page.translated || page.translating);
        final String[] from = {null};
        final String[] to = {null};
        if (page != null) {
            from[0] = TranslationLanguages.match(active ? page.from : page.docLang, fromCodes);
            to[0] = TranslationLanguages.match(active ? page.to : translator.targetLanguage(), toCodes);
        } else {
            to[0] = TranslationLanguages.match(translator.targetLanguage(), toCodes);
        }
        final boolean[] never = {page != null && page.neverSite};

        final Runnable refresh = () -> {
            fromView.setText(from[0] == null ? "Choose a language" : translator.languageName(from[0]));
            toView.setText(to[0] == null ? "Choose a language" : translator.languageName(to[0]));
            offersToggle.setText(translator.offersEnabled() ? "On" : "Off");
            setChip(offersToggle, translator.offersEnabled());

            if (page == null) {
                status.setText("Choose the language that pages are translated into, and what "
                        + "happens when a page is in another language.");
                targetRow.setText("Translate pages into: "
                        + (to[0] == null ? "not set" : translator.languageName(to[0])));
                return;
            }
            if (page.translated) {
                status.setText("Translated from " + translator.languageName(page.from)
                        + " to " + translator.languageName(page.to));
            } else if (page.translating) {
                status.setText("Translating\u2026");
            } else if (page.docLang == null) {
                status.setText("The language of this page isn't known yet. Choose it below.");
            } else if (!page.docSupported) {
                status.setText("This page looks like "
                        + translator.languageName(page.docLang)
                        + ", which can't be translated yet. You can still choose a language below.");
            } else {
                status.setText("This page is in " + translator.languageName(page.docLang));
            }

            final boolean haveBoth = from[0] != null && to[0] != null;
            final boolean same = haveBoth && TranslationLanguages.sameLanguage(from[0], to[0]);
            final boolean samePair = active && haveBoth
                    && from[0].equalsIgnoreCase(page.from) && to[0].equalsIgnoreCase(page.to);
            action.setText(samePair ? "Show original" : active ? "Translate again" : "Translate");
            action.setAlpha(samePair || (haveBoth && !same) ? 1f : 0.45f);

            final boolean haveFrom = from[0] != null;
            ruleLabel.setVisibility(haveFrom ? View.VISIBLE : View.GONE);
            ruleRow.setVisibility(haveFrom ? View.VISIBLE : View.GONE);
            if (haveFrom) {
                ruleLabel.setText("When a page is in " + translator.languageName(from[0]));
                final String rule = translator.ruleFor(from[0]);
                setChip(ruleAsk, RuntimeTranslation.OFFER.equals(rule));
                setChip(ruleAlways, RuntimeTranslation.ALWAYS.equals(rule));
                setChip(ruleNever, RuntimeTranslation.NEVER.equals(rule));
            }
            siteToggle.setText(never[0] ? "On" : "Off");
            setChip(siteToggle, never[0]);
        };

        if (page == null) {
            pageGroup.setVisibility(View.GONE);
            targetRow.setVisibility(View.VISIBLE);
        } else {
            pageGroup.setVisibility(View.VISIBLE);
            targetRow.setVisibility(View.GONE);
        }

        fromView.setOnClickListener(v -> pickLanguage("Translate from",
                translator.fromLanguages(), from[0], lang -> {
                    from[0] = lang.code;
                    refresh.run();
                }));
        toView.setOnClickListener(v -> pickLanguage("Translate to",
                translator.toLanguages(), to[0], lang -> {
                    to[0] = lang.code;
                    refresh.run();
                }));
        targetRow.setOnClickListener(v -> pickLanguage("Translate pages into",
                translator.toLanguages(), to[0], lang -> {
                    to[0] = lang.code;
                    translator.setTarget(lang.code);
                    refresh.run();
                }));

        action.setOnClickListener(v -> {
            if (page == null) return;
            if (from[0] == null || to[0] == null) {
                translator.toast("Choose the languages first");
                return;
            }
            if (TranslationLanguages.sameLanguage(from[0], to[0])) {
                translator.toast("Choose two different languages");
                return;
            }
            final boolean samePair = active
                    && from[0].equalsIgnoreCase(page.from) && to[0].equalsIgnoreCase(page.to);
            d.dismiss();
            if (samePair) translator.restoreFromDialog();
            else translator.translateFromDialog(from[0], to[0]);
        });

        ruleAsk.setOnClickListener(v -> setRuleFromDialog(from[0], RuntimeTranslation.OFFER, refresh));
        ruleAlways.setOnClickListener(v -> setRuleFromDialog(from[0], RuntimeTranslation.ALWAYS, refresh));
        ruleNever.setOnClickListener(v -> setRuleFromDialog(from[0], RuntimeTranslation.NEVER, refresh));

        siteToggle.setOnClickListener(v -> {
            never[0] = !never[0];
            translator.setNeverSite(never[0]);
            refresh.run();
        });
        offersToggle.setOnClickListener(v -> {
            translator.setOffersEnabled(!translator.offersEnabled());
            refresh.run();
        });
        d.findViewById(R.id.tr_rules).setOnClickListener(v -> showRules());
        d.findViewById(R.id.tr_close).setOnClickListener(v -> d.dismiss());

        refresh.run();
        d.show();
    }

    private void setRuleFromDialog(String code, String rule, Runnable refresh) {
        if (code == null) return;
        translator.setRule(code, rule, refresh);
    }

    // ------------------------------------------------------------------
    // A searchable list of languages
    // ------------------------------------------------------------------

    private void pickLanguage(String title, List<TranslationLanguages.Lang> options,
                              String selectedCode, final Consumer<TranslationLanguages.Lang> onPick) {
        if (finishing()) return;
        // Three quarters of the app window's height (the list takes what is left), kept in step
        // when the window is resized.
        final Dialog d = newDialog(R.layout.dialog_language_picker, 0.92f, 0.75f);
        ((TextView) d.findViewById(R.id.lp_title)).setText(title);
        final EditText search = d.findViewById(R.id.lp_search);
        final ListView list = d.findViewById(R.id.lp_list);
        final TextView empty = d.findViewById(R.id.lp_empty);

        final LanguageAdapter adapter = new LanguageAdapter(options, selectedCode);
        list.setAdapter(adapter);
        final Runnable showHide = () -> {
            final boolean none = adapter.getCount() == 0;
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
        };
        showHide.run();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
                showHide.run();
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            final TranslationLanguages.Lang lang = adapter.getItem(position);
            d.dismiss();
            onPick.accept(lang);
        });
        d.findViewById(R.id.lp_close).setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private final class LanguageAdapter extends BaseAdapter {
        private final List<TranslationLanguages.Lang> all;
        private final String selected;
        private List<TranslationLanguages.Lang> shown;

        LanguageAdapter(List<TranslationLanguages.Lang> all, String selected) {
            this.all = all == null ? new ArrayList<TranslationLanguages.Lang>() : all;
            this.selected = selected;
            this.shown = new ArrayList<>(this.all);
        }

        void filter(String query) {
            shown = TranslationLanguages.filter(all, query);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public TranslationLanguages.Lang getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            final View v = cv != null ? cv
                    : LayoutInflater.from(activity).inflate(R.layout.row_item, parent, false);
            final TranslationLanguages.Lang lang = shown.get(pos);
            final boolean isSelected = selected != null && selected.equalsIgnoreCase(lang.code);
            final TextView title = v.findViewById(R.id.row_title);
            final TextView sub = v.findViewById(R.id.row_subtitle);
            title.setText(isSelected ? "\u2713  " + lang.name : lang.name);
            title.setTextColor(0xFFFFFFFF);
            sub.setText(lang.code);
            sub.setTextColor(0x66FFFFFF);
            return v;
        }
    }

    // ------------------------------------------------------------------
    // Language rules
    // ------------------------------------------------------------------

    /** Opens the list of rules once they are known. */
    void showRules() {
        showRules(null);
    }

    void showRules(final Runnable onChanged) {
        if (finishing()) return;
        translator.whenRulesLoaded(() -> buildRules(onChanged));
    }

    private void buildRules(final Runnable onChanged) {
        if (finishing()) return;
        final Dialog d = newDialog(R.layout.dialog_language_rules, 0.92f, 0f);
        final ListView list = d.findViewById(R.id.lr_list);
        final TextView empty = d.findViewById(R.id.lr_empty);
        final RuleAdapter adapter = new RuleAdapter();
        list.setAdapter(adapter);

        final Runnable reload = () -> {
            adapter.set(currentRules());
            final boolean none = adapter.getCount() == 0;
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            fitHeight(list, adapter);
            if (onChanged != null) onChanged.run();
        };
        reload.run();
        // [RESIZE] How tall the list may be depends on the window: work it out again on a resize.
        DialogFit.followResize(activity, d, () -> {
            if (list.getVisibility() == View.VISIBLE) fitHeight(list, adapter);
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            final RuleRow row = adapter.getItem(position);
            chooseRule(row.name, row.rule,
                    rule -> translator.setRule(row.code, rule, reload));
        });
        d.findViewById(R.id.lr_add).setOnClickListener(v -> translator.whenLanguagesLoaded(
                () -> pickLanguage("Add a language", translator.fromLanguages(), null, lang ->
                        chooseRule(lang.name, translator.ruleFor(lang.code),
                                rule -> translator.setRule(lang.code, rule, reload)))));
        d.findViewById(R.id.lr_close).setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private static final class RuleRow {
        final String code;
        final String name;
        final String rule;

        RuleRow(String code, String name, String rule) {
            this.code = code;
            this.name = name;
            this.rule = rule;
        }
    }

    /** Languages with a rule other than the default, by name. */
    private List<RuleRow> currentRules() {
        final List<TranslationLanguages.Lang> langs = new ArrayList<>();
        final Map<String, String> custom = translator.customRules();
        for (String code : custom.keySet()) {
            langs.add(new TranslationLanguages.Lang(code, translator.languageName(code)));
        }
        final List<RuleRow> out = new ArrayList<>();
        for (TranslationLanguages.Lang l : TranslationLanguages.sorted(langs)) {
            out.add(new RuleRow(l.code, l.name, custom.get(l.code)));
        }
        return out;
    }

    private static String ruleText(String rule) {
        if (RuntimeTranslation.ALWAYS.equals(rule)) return "Always translate";
        if (RuntimeTranslation.NEVER.equals(rule)) return "Never translate";
        return "Ask each time";
    }

    private final class RuleAdapter extends BaseAdapter {
        private List<RuleRow> rows = new ArrayList<>();

        void set(List<RuleRow> newRows) {
            rows = newRows;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public RuleRow getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            final View v = cv != null ? cv
                    : LayoutInflater.from(activity).inflate(R.layout.row_item, parent, false);
            final RuleRow row = rows.get(pos);
            final TextView title = v.findViewById(R.id.row_title);
            final TextView sub = v.findViewById(R.id.row_subtitle);
            title.setText(row.name);
            title.setTextColor(0xFFFFFFFF);
            sub.setText(ruleText(row.rule));
            sub.setTextColor(0x66FFFFFF);
            return v;
        }
    }

    /**
     * The tallest the list of rules may be: the usual 260dp on a normal screen, less when the
     * window is short (split screen, landscape), so the buttons under the list stay in view.
     */
    private int maxListHeight() {
        final int room = (int) (DialogFit.windowHeight(activity) * 0.92f) - dp(RULES_CHROME_DP);
        return Math.max(dp(LIST_MIN_DP), Math.min(dp(LIST_MAX_DP), room));
    }

    /** The list is as tall as its rows, up to a limit (a ListView ignores maxHeight). */
    private void fitHeight(ListView list, BaseAdapter adapter) {
        final int max = maxListHeight();
        int total = 0;
        for (int i = 0; i < adapter.getCount() && total < max; i++) {
            final View row = adapter.getView(i, null, list);
            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            total += row.getMeasuredHeight();
        }
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = total == 0 ? WRAP : Math.min(total, max);
        list.setLayoutParams(lp);
    }

    /** Pick one of the three rules for a language. */
    private void chooseRule(String languageName, String current, final Consumer<String> onChosen) {
        if (finishing()) return;
        final Dialog d = newDialog(R.layout.dialog_choice, 0.85f, 0f);
        ((TextView) d.findViewById(R.id.ch_title)).setText("When a page is in " + languageName);
        final String[] rules = {RuntimeTranslation.ALWAYS, RuntimeTranslation.NEVER, RuntimeTranslation.OFFER};
        final int[] ids = {R.id.ch_opt1, R.id.ch_opt2, R.id.ch_opt3};
        for (int i = 0; i < rules.length; i++) {
            final String rule = rules[i];
            final TextView opt = d.findViewById(ids[i]);
            opt.setText(ruleText(rule));
            setChip(opt, rule.equals(current));
            opt.setOnClickListener(v -> {
                d.dismiss();
                onChosen.accept(rule);
            });
        }
        d.findViewById(R.id.ch_cancel).setOnClickListener(v -> d.dismiss());
        d.show();
    }
}
