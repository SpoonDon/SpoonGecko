package com.spoongecko.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal home screen: brand, tagline, search bar.
 * No quick links, no recent list.
 */
public final class HomeView extends LinearLayout {

    public interface Callbacks {
        void onNavigate(String url);
        void onSearch(String query);
    }

    private static final int COL_BG     = 0xFF121212;
    private static final int COL_CARD   = 0xFF1E1E1E;
    private static final int COL_TEXT   = 0xFFEEEEEE;
    private static final int COL_MUTED  = 0xFF9A9A9A;
    private static final int COL_ACCENT = 0xFF4D8DFF;

    private final Callbacks cb;
    private EditText searchBar;

    public HomeView(Context ctx, Callbacks cb) {
        super(ctx);
        this.cb = cb;
        setOrientation(VERTICAL);
        setBackgroundColor(COL_BG);
        build();
    }

    public void refresh() {
        if (searchBar != null) searchBar.setText("");
    }

    public void focusSearchBar() {
        if (searchBar == null) return;
        searchBar.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
    }

    // ---------------------------------------------------------------- build

    private void build() {
        ScrollView sv = new ScrollView(getContext());
        sv.setFillViewport(true);
        addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        int p = dp(20);
        col.setPadding(p, dp(48), p, p);
        sv.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView brand = new TextView(getContext());
        brand.setText("SpoonGecko");
        brand.setTextColor(COL_TEXT);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(brand);

        TextView tagline = new TextView(getContext());
        tagline.setText("Private, fast, yours.");
        tagline.setTextColor(COL_MUTED);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        col.addView(tagline, tlp);

        LinearLayout searchRow = new LinearLayout(getContext());
        searchRow.setOrientation(HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(rounded(COL_CARD, dp(24)));
        searchRow.setPadding(dp(18), 0, dp(6), 0);
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(28);
        col.addView(searchRow, slp);

        searchBar = new EditText(getContext());
        searchBar.setHint("Search or enter address");
        searchBar.setHintTextColor(COL_MUTED);
        searchBar.setTextColor(COL_TEXT);
        searchBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchBar.setSingleLine(true);
        searchBar.setBackground(null);
        searchBar.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchBar.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        searchRow.addView(searchBar, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        TextView go = new TextView(getContext());
        go.setText("Go");
        go.setTextColor(0xFFFFFFFF);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(rounded(COL_ACCENT, dp(18)));
        go.setPadding(dp(18), 0, dp(18), 0);
        go.setOnClickListener(v -> submit());
        searchRow.addView(go, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)));
    }

    // -------------------------------------------------------------- submit

    private void submit() {
        String q = searchBar.getText().toString().trim();
        if (q.isEmpty()) return;
        searchBar.setText("");
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        if (looksLikeUrl(q)) cb.onNavigate(normalizeUrl(q));
        else cb.onSearch(q);
    }

    // --------------------------------------------------------------- util

    private static boolean looksLikeUrl(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.contains("://")) return true;
        String lower = s.toLowerCase();
        if (lower.startsWith("about:")  || lower.startsWith("data:")   ||
            lower.startsWith("file:")   || lower.startsWith("magnet:") ||
            lower.startsWith("tel:")    || lower.startsWith("mailto:") ||
            lower.startsWith("market:") || lower.startsWith("intent:")) return true;
        if (s.indexOf(' ') >= 0) return false;
        String head = s;
        int slash = s.indexOf('/');
        if (slash >= 0) head = s.substring(0, slash);
        return head.contains(".") && !head.startsWith(".") && !head.endsWith(".");
    }

    private static String normalizeUrl(String s) {
        if (s.contains("://")) return s;
        String lower = s.toLowerCase();
        if (lower.startsWith("about:")  || lower.startsWith("data:")   ||
            lower.startsWith("file:")   || lower.startsWith("magnet:") ||
            lower.startsWith("tel:")    || lower.startsWith("mailto:") ||
            lower.startsWith("market:") || lower.startsWith("intent:")) return s;
        return "https://" + s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }
}
