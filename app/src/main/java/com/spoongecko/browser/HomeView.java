package com.spoongecko.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * New-tab home screen: search bar, quick-links grid, recent sites.
 * Purely native — no GeckoView involvement. MainActivity toggles visibility
 * between this view and the GeckoView depending on whether the user is on
 * the home screen or on a real page.
 */
public final class HomeView extends LinearLayout {

    public interface Callbacks {
        void onNavigate(String url);
        void onSearch(String query);
        void onAddQuickLink();
        void onEditQuickLink(HomeStore.QuickLink link);
    }

    private static final int COL_BG     = 0xFF121212;
    private static final int COL_CARD   = 0xFF1E1E1E;
    private static final int COL_TEXT   = 0xFFEEEEEE;
    private static final int COL_MUTED  = 0xFF9A9A9A;
    private static final int COL_ACCENT = 0xFF4D8DFF;

    private final Callbacks cb;
    private EditText searchBar;
    private LinearLayout quickLinks;
    private LinearLayout recentHeaderRow;
    private LinearLayout recentList;

    public HomeView(Context ctx, Callbacks cb) {
        super(ctx);
        this.cb = cb;
        setOrientation(VERTICAL);
        setBackgroundColor(COL_BG);
        build();
    }

    public void refresh() {
        renderQuickLinks();
        renderRecent();
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
        col.setPadding(p, p, p, p);
        sv.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Brand
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

        // Search row
        LinearLayout searchRow = new LinearLayout(getContext());
        searchRow.setOrientation(HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(rounded(COL_CARD, dp(24)));
        searchRow.setPadding(dp(18), 0, dp(6), 0);
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(20);
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

        // Quick links header
        col.addView(sectionHeader("Quick Links", dp(28)));

        quickLinks = new LinearLayout(getContext());
        quickLinks.setOrientation(VERTICAL);
        LayoutParams qlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(10);
        col.addView(quickLinks, qlp);

        // Recent header (title + clear)
        recentHeaderRow = new LinearLayout(getContext());
        recentHeaderRow.setOrientation(HORIZONTAL);
        recentHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rhLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rhLp.topMargin = dp(28);
        col.addView(recentHeaderRow, rhLp);

        TextView rTitle = new TextView(getContext());
        rTitle.setText("Recent");
        rTitle.setTextColor(COL_TEXT);
        rTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        rTitle.setTypeface(Typeface.DEFAULT_BOLD);
        recentHeaderRow.addView(rTitle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(getContext());
        clear.setText("Clear");
        clear.setTextColor(COL_ACCENT);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clear.setPadding(dp(8), dp(4), 0, dp(4));
        clear.setOnClickListener(v -> {
            VisitStore.clearAll(getContext());
            renderRecent();
        });
        recentHeaderRow.addView(clear);

        recentList = new LinearLayout(getContext());
        recentList.setOrientation(VERTICAL);
        LayoutParams rlLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rlLp.topMargin = dp(10);
        col.addView(recentList, rlLp);
    }

    private TextView sectionHeader(String text, int topMargin) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(COL_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        tv.setLayoutParams(lp);
        return tv;
    }

    // -------------------------------------------------------------- render

    private void renderQuickLinks() {
        quickLinks.removeAllViews();
        List<HomeStore.QuickLink> links = HomeStore.getQuickLinks(getContext());
        int total = links.size() + 1; // trailing "+" tile
        int cols = 4;

        for (int i = 0; i < total; i += cols) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            LayoutParams rowLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = dp(12);
            row.setLayoutParams(rowLp);

            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                View tile;
                if (idx < links.size()) {
                    tile = buildQuickTile(links.get(idx));
                } else if (idx == links.size()) {
                    tile = buildAddTile();
                } else {
                    tile = new View(getContext());
                }
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        0, LayoutParams.WRAP_CONTENT, 1f);
                row.addView(tile, tlp);
            }
            quickLinks.addView(row);
        }
    }

    private void renderRecent() {
        recentList.removeAllViews();
        List<VisitStore.Visit> visits = VisitStore.getRecentForHome(getContext());
        if (visits.isEmpty()) {
            recentHeaderRow.setVisibility(GONE);
            recentList.setVisibility(GONE);
            return;
        }
        recentHeaderRow.setVisibility(VISIBLE);
        recentList.setVisibility(VISIBLE);
        for (VisitStore.Visit v : visits) {
            recentList.addView(buildRecentRow(v));
        }
    }

    private View buildQuickTile(HomeStore.QuickLink link) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(8), dp(4), dp(8));

        TextView circle = new TextView(getContext());
        circle.setText(initial(link.title, link.url));
        circle.setTextColor(0xFFFFFFFF);
        circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        circle.setTypeface(Typeface.DEFAULT_BOLD);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(rounded(colorFor(link.url), dp(24)));
        tile.addView(circle, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView t = new TextView(getContext());
        t.setText(link.title);
        t.setTextColor(COL_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        tile.addView(t, tlp);

        tile.setOnClickListener(v -> cb.onNavigate(link.url));
        tile.setOnLongClickListener(v -> { cb.onEditQuickLink(link); return true; });
        return tile;
    }

    private View buildAddTile() {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(8), dp(4), dp(8));

        TextView circle = new TextView(getContext());
        circle.setText("+");
        circle.setTextColor(COL_ACCENT);
        circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable bg = rounded(COL_CARD, dp(24));
        bg.setStroke(dp(1), COL_ACCENT);
        circle.setBackground(bg);
        tile.addView(circle, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView t = new TextView(getContext());
        t.setText("Add");
        t.setTextColor(COL_MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        tile.addView(t, tlp);

        tile.setOnClickListener(v -> cb.onAddQuickLink());
        return tile;
    }

    private View buildRecentRow(VisitStore.Visit v) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));

        TextView circle = new TextView(getContext());
        circle.setText(initial(v.title, v.url));
        circle.setTextColor(0xFFFFFFFF);
        circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        circle.setTypeface(Typeface.DEFAULT_BOLD);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(rounded(colorFor(v.url), dp(18)));
        row.addView(circle, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        row.addView(col, clp);

        TextView title = new TextView(getContext());
        title.setText(v.title);
        title.setTextColor(COL_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        TextView host = new TextView(getContext());
        host.setText(hostOf(v.url));
        host.setTextColor(COL_MUTED);
        host.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        host.setMaxLines(1);
        host.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(2);
        col.addView(host, hlp);

        row.setOnClickListener(view -> cb.onNavigate(v.url));
        return row;
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

    private static String initial(String title, String url) {
        String src = (title != null && !title.isEmpty()) ? title : url;
        if (src == null || src.isEmpty()) return "?";
        char c = src.charAt(0);
        return Character.isLetterOrDigit(c)
                ? String.valueOf(Character.toUpperCase(c)) : "?";
    }

    private static String hostOf(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost();
            if (h == null) return url;
            if (h.startsWith("www.")) h = h.substring(4);
            return h;
        } catch (Exception e) {
            return url;
        }
    }

    private static int colorFor(String url) {
        int[] palette = {
            0xFF4D8DFF, 0xFFE06666, 0xFFF6B26B, 0xFF93C47D,
            0xFF8E7CC3, 0xFF76A5AF, 0xFFD5A6BD, 0xFFA4C2F4
        };
        if (url == null || url.isEmpty()) return palette[0];
        int h = 0;
        for (int i = 0; i < url.length(); i++) h = h * 31 + url.charAt(i);
        return palette[Math.abs(h) % palette.length];
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
