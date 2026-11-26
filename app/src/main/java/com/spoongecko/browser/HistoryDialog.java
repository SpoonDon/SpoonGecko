package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * [HISTORY] The history dialog, with a choice of order:
 *
 *  - Newest: latest visit first, with a heading per day and the time of each visit.
 *  - Oldest: earliest visit first, same layout.
 *  - Site:   grouped by site, A to Z, with the date and time of each visit.
 *
 * The chosen order is remembered. It looks like the other dialogs (BrowserDialogs.showHistory,
 * which is no longer used, is left in place) and keeps their behaviour: tap a page to open it,
 * "Clear" deletes the history, "Close" leaves it alone.
 *
 * Every page line has a small X that removes just that page from the history (see
 * HistoryStore.remove). It is gone from the list at once and the list stays where it was.
 */
final class HistoryDialog {

    private static final String TAG = "SpoonGecko";
    /** Pages loaded per list. The old dialog showed the latest 100; this is enough to sort. */
    private static final int LIMIT = 1000;
    private static final int LIST_MAX_DP = 360;
    /** The list never gets shorter than this, however small the window becomes. */
    private static final int LIST_MIN_DP = 96;
    /**
     * Height of everything in the dialog except the list (title, order chips, buttons, padding),
     * with some room for a larger font. Subtracted from the window height to find how tall the
     * list may be.
     */
    private static final int CHROME_DP = 180;
    /** The dialog is this fraction of the app window's width, and of its height at most. */
    private static final float WIDTH_FRACTION = 0.9f;
    private static final float HEIGHT_FRACTION = 0.92f;
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_SORT = "history_sort";

    interface Actions {
        void onOpenDefault(String url);
        void onOpenInNewTab(String url);
        void onOpenInNewIncognitoTab(String url);
        void onOpenInNewWindow(String url);
        void onOpenInNewIncognitoWindow(String url);
    }

    private final Activity activity;
    private final HistoryStore history;
    private Dialog dialog;
    private Dialog contextDialog;

    /**
     * [RESIZE] Watches the app window while the dialog is open (see followWindowSize). The
     * dialog is a window of its own: when the app window changes size (split screen, a resized
     * freeform window, fold/unfold, rotation) Android does not resize it, so its size, worked
     * out once when it opened, had to be worked out again.
     */
    private View.OnLayoutChangeListener windowWatcher;
    private boolean refitQueued;

    HistoryDialog(Activity activity, HistoryStore history) {
        this.activity = activity;
        this.history = history;
    }

    void show(final Consumer<String> onNavigate) {
        show(new Actions() {
            @Override public void onOpenDefault(String url) {
                if (onNavigate != null) onNavigate.accept(url);
            }
            @Override public void onOpenInNewTab(String url) {
                if (onNavigate != null) onNavigate.accept(url);
            }
            @Override public void onOpenInNewIncognitoTab(String url) {
                if (onNavigate != null) onNavigate.accept(url);
            }
            @Override public void onOpenInNewWindow(String url) {
                if (onNavigate != null) onNavigate.accept(url);
            }
            @Override public void onOpenInNewIncognitoWindow(String url) {
                if (onNavigate != null) onNavigate.accept(url);
            }
        });
    }

    void show(final Actions actions) {
        if (activity.isFinishing() || activity.isDestroyed() || actions == null) return;
        if (dialog != null && dialog.isShowing()) return;

        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_history);
        final Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.setLayout(dialogWidthPx(), WindowManager.LayoutParams.WRAP_CONTENT);
        }

        final ListView list = d.findViewById(R.id.dlg_list);
        final TextView empty = d.findViewById(R.id.dlg_empty);
        final TextView[] chips = {
                d.findViewById(R.id.sort_newest),
                d.findViewById(R.id.sort_oldest),
                d.findViewById(R.id.sort_site)
        };
        final HistoryRows.Mode[] modes = HistoryRows.Mode.values();   // same order as the chips
        final HistoryRows.Mode[] current = {readMode()};

        // Pages removed from this dialog. The delete is written on the history's write thread, so
        // a list that is read again straight away (another order chosen, another page removed)
        // could still see them; this set keeps them out until the database has caught up.
        final Set<String> removed = new HashSet<>();

        final RowAdapter adapter = new RowAdapter();
        list.setAdapter(adapter);
        final int[] loadGen = {0};

        final Runnable load = () -> {
            final HistoryRows.Mode targetMode = current[0];
            final int token = ++loadGen[0];
            updateChips(targetMode, modes, chips);
            history.loadRowsAsync(targetMode, LIMIT, new HashSet<>(removed),
                    ZoneId.systemDefault(), Locale.getDefault(), rows -> {
                        if (token != loadGen[0] || dialog != d
                                || activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        adapter.set(rows);
                        final boolean none = rows.isEmpty();
                        empty.setVisibility(none ? View.VISIBLE : View.GONE);
                        list.setVisibility(none ? View.GONE : View.VISIBLE);
                        if (!none) {
                            fitListHeight(list, adapter);
                            list.setSelection(0);
                        }
                    });
        };
        for (int i = 0; i < chips.length; i++) {
            final HistoryRows.Mode m = modes[i];
            chips[i].setOnClickListener(v -> {
                if (current[0] == m) return;
                current[0] = m;
                saveMode(m);
                load.run();
            });
        }

        adapter.setOnOpen(entry -> {
            if (entry == null || entry.url == null || entry.url.trim().isEmpty()) return;
            actions.onOpenDefault(entry.url);
            d.dismiss();
        });
        adapter.setOnLongPress(entry -> {
            if (entry == null || entry.url == null || entry.url.trim().isEmpty()) return;
            showItemActionsDialog(d, entry, actions);
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            final HistoryRows.Row row = adapter.getItem(position);
            if (row == null || row.entry == null) return;       // a section heading
            actions.onOpenDefault(row.entry.url);
            d.dismiss();
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= adapter.getCount()) return false;
            final HistoryRows.Row row = adapter.getItem(position);
            if (row == null || row.entry == null
                    || row.entry.url == null || row.entry.url.trim().isEmpty()) {
                return false;
            }
            showItemActionsDialog(d, row.entry, actions);
            return true;
        });

        // The X on a page line: remove that page only, and leave the list where it was (the
        // user is usually clearing several pages in a row). A day or site heading whose last
        // page has gone disappears with it, and in-memory filtering avoids a blocking query.
        adapter.setOnRemove(entry -> {
            removed.add(entry.url);
            history.remove(entry.url);
            final int first = list.getFirstVisiblePosition();
            final View firstRow = list.getChildAt(0);
            final int offset = firstRow == null ? 0 : firstRow.getTop();
            adapter.removeUrl(entry.url, current[0] == HistoryRows.Mode.SITE);
            final boolean none = adapter.getCount() == 0;
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            if (!none) {
                fitListHeight(list, adapter);
                list.setSelectionFromTop(Math.min(first, adapter.getCount() - 1), offset);
            }
        });

        final TextView neg = d.findViewById(R.id.dlg_negative);
        final TextView pos = d.findViewById(R.id.dlg_positive);
        neg.setOnClickListener(v -> d.dismiss());
        pos.setOnClickListener(v -> {
            try {
                history.clearAsync();
            } catch (RuntimeException e) {
                Log.w(TAG, "history could not be cleared", e);
            }
            d.dismiss();
        });

        d.setOnDismissListener(x -> {
            loadGen[0]++;
            dismissContextDialog();
            if (dialog == d) dialog = null;
            stopFollowingWindowSize();
        });
        dialog = d;
        load.run();
        d.show();
        followWindowSize(d, list, adapter);
    }

    private void showItemActionsDialog(final Dialog parentDialog,
                                       final HistoryStore.Entry entry,
                                       final Actions actions) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dismissContextDialog();

        final Dialog ctx = new Dialog(activity);
        ctx.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final ScrollView scroll = new ScrollView(activity);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_glass_dialog);
        final int pad = dp(18);
        content.setPadding(pad, pad, pad, dp(14));

        final String shortUrl = SuggestionRanker.shortUrl(entry.url);
        final String displayTitle = (entry.title == null || entry.title.trim().isEmpty())
                ? shortUrl : entry.title.trim();

        final TextView titleView = new TextView(activity);
        titleView.setText(displayTitle);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(titleView);

        final TextView subView = new TextView(activity);
        subView.setText(shortUrl);
        subView.setTextColor(0x88FFFFFF);
        subView.setTextSize(12);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subView.setPadding(0, dp(3), 0, dp(10));
        content.addView(subView);

        final BookmarkRepository repo = BookmarkRepository.get(activity);
        final boolean alreadyBookmarked = repo.isCachedBookmarked(entry.url);
        final String bookmarkLabel = alreadyBookmarked ? "Remove bookmark" : "Bookmark";
        addActionRow(content, bookmarkLabel, false, () -> {
            ctx.dismiss();
            if (!BookmarkRepository.isBookmarkable(entry.url, entry.title)) {
                Toast.makeText(activity, "This page can't be bookmarked", Toast.LENGTH_SHORT).show();
                return;
            }
            repo.toggleBookmark(entry.url, entry.title, state -> {
                if (activity.isFinishing() || activity.isDestroyed() || state == null) return;
                Toast.makeText(activity, state ? "Bookmarked" : "Bookmark removed",
                        Toast.LENGTH_SHORT).show();
            });
        });

        addActionRow(content, "Open in new tab", false, () -> {
            ctx.dismiss();
            parentDialog.dismiss();
            actions.onOpenInNewTab(entry.url);
        });

        addActionRow(content, "Open in new incognito tab", false, () -> {
            ctx.dismiss();
            parentDialog.dismiss();
            actions.onOpenInNewIncognitoTab(entry.url);
        });

        addActionRow(content, "Open in new window", false, () -> {
            ctx.dismiss();
            parentDialog.dismiss();
            actions.onOpenInNewWindow(entry.url);
        });

        addActionRow(content, "Open in new incognito window", false, () -> {
            ctx.dismiss();
            parentDialog.dismiss();
            actions.onOpenInNewIncognitoWindow(entry.url);
        });

        addActionRow(content, "Cancel", true, ctx::dismiss);

        scroll.addView(content);
        ctx.setContentView(scroll);
        final Window w = ctx.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.55f);
        }
        ctx.setCanceledOnTouchOutside(true);
        ctx.setOnDismissListener(x -> {
            if (contextDialog == ctx) contextDialog = null;
        });
        contextDialog = ctx;
        DialogFit.fitAndFollow(activity, ctx, 0.86f, 0f, null);
        ctx.show();
    }

    private void addActionRow(LinearLayout parent, String label, boolean muted, Runnable onClick) {
        final TextView btn = new TextView(activity);
        btn.setText(label);
        btn.setTextColor(muted ? 0xB3FFFFFF : 0xFFFFFFFF);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        btn.setMinHeight(dp(48));
        btn.setBackgroundResource(R.drawable.bg_glass_button);
        btn.setClickable(true);
        btn.setFocusable(true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn, lp);
    }

    private void dismissContextDialog() {
        final Dialog ctx = contextDialog;
        contextDialog = null;
        if (ctx != null) {
            try { ctx.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // [RESIZE] Keeping the dialog the right size when the app window changes size
    // ------------------------------------------------------------------

    /** Width of the app window in pixels (its real size, which a split screen or freeform window changes). */
    private int windowWidthPx() {
        final int w = activity.getWindow().getDecorView().getWidth();
        return w > 0 ? w : activity.getResources().getDisplayMetrics().widthPixels;
    }

    private int windowHeightPx() {
        final int h = activity.getWindow().getDecorView().getHeight();
        return h > 0 ? h : activity.getResources().getDisplayMetrics().heightPixels;
    }

    private int dialogWidthPx() {
        return (int) (windowWidthPx() * WIDTH_FRACTION);
    }

    /**
     * The tallest the list may be: the usual 360dp on a normal screen, less when the window is
     * short (split screen, landscape), so the buttons below the list stay on the screen.
     */
    private int maxListHeightPx() {
        final int room = (int) (windowHeightPx() * HEIGHT_FRACTION) - dp(CHROME_DP);
        return Math.max(dp(LIST_MIN_DP), Math.min(dp(LIST_MAX_DP), room));
    }

    /**
     * Starts watching the app window. When its size changes the dialog is fitted to the new size.
     * The work is posted and coalesced: dragging the edge of a freeform window reports every
     * pixel, and the dialog is a separate window that must not be changed in the middle of the
     * app window's own layout pass.
     */
    private void followWindowSize(final Dialog d, final ListView list, final RowAdapter adapter) {
        stopFollowingWindowSize();
        final View decor = activity.getWindow().getDecorView();
        final View.OnLayoutChangeListener watcher = (v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) == (or - ol) && (b - t) == (ob - ot)) return;   // moved, not resized
            if (refitQueued) return;
            refitQueued = true;
            v.post(() -> {
                refitQueued = false;
                if (dialog == d) fitToWindow(d, list, adapter);
            });
        };
        windowWatcher = watcher;
        decor.addOnLayoutChangeListener(watcher);
    }

    private void stopFollowingWindowSize() {
        final View.OnLayoutChangeListener watcher = windowWatcher;
        windowWatcher = null;
        refitQueued = false;
        if (watcher != null) {
            activity.getWindow().getDecorView().removeOnLayoutChangeListener(watcher);
        }
    }

    /** Sets the dialog's width and the list's height again from the window's current size. */
    private void fitToWindow(Dialog d, ListView list, RowAdapter adapter) {
        if (!d.isShowing()) return;
        final Window w = d.getWindow();
        if (w != null) w.setLayout(dialogWidthPx(), WindowManager.LayoutParams.WRAP_CONTENT);
        if (list.getVisibility() == View.VISIBLE && adapter.getCount() > 0) {
            fitListHeight(list, adapter);
        }
    }

    /** Closes the dialog if it is showing (called when the activity is destroyed). */
    void dismiss() {
        dismissContextDialog();
        final Dialog d = dialog;
        dialog = null;
        stopFollowingWindowSize();
        if (d != null) {
            try { d.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private static void updateChips(HistoryRows.Mode mode, HistoryRows.Mode[] modes, TextView[] chips) {
        for (int i = 0; i < chips.length; i++) {
            final boolean selected = modes[i] == mode;
            chips[i].setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chips[i].setTextColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
        }
    }

    /**
     * ListView ignores android:maxHeight, so the height is measured from the rows and capped
     * here (reusing one convertView per row type to avoid inflating layouts repeatedly).
     */
    private void fitListHeight(ListView list, RowAdapter adapter) {
        final int max = maxListHeightPx();
        int total = 0;
        View headerView = null;
        View itemView = null;
        for (int i = 0; i < adapter.getCount() && total < max; i++) {
            final boolean isHeader = adapter.getItem(i).isHeader();
            final View row = adapter.getView(i, isHeader ? headerView : itemView, list);
            if (isHeader) headerView = row;
            else itemView = row;
            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            total += row.getMeasuredHeight();
        }
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        final int target = total == 0 ? max : Math.min(total, max);
        if (lp.height != target) {
            lp.height = target;
            list.setLayoutParams(lp);
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private HistoryRows.Mode readMode() {
        try {
            return HistoryRows.Mode.valueOf(
                    prefs().getString(PREF_SORT, HistoryRows.Mode.NEWEST.name()));
        } catch (RuntimeException e) {
            return HistoryRows.Mode.NEWEST;
        }
    }

    private void saveMode(HistoryRows.Mode mode) {
        try {
            prefs().edit().putString(PREF_SORT, mode.name()).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "history order could not be saved", e);
        }
    }

    // ------------------------------------------------------------------
    // List rows: section headings and pages
    // ------------------------------------------------------------------

    private static final class RowViewHolder {
        final boolean isHeader;
        final TextView headerText;
        final View mainArea;
        final TextView title;
        final TextView sub;
        final TextView time;
        final View delete;
        HistoryStore.Entry boundEntry;

        RowViewHolder(View headerView) {
            this.isHeader = true;
            this.headerText = (TextView) headerView;
            this.mainArea = null;
            this.title = null;
            this.sub = null;
            this.time = null;
            this.delete = null;
        }

        RowViewHolder(View itemView, boolean unused) {
            this.isHeader = false;
            this.headerText = null;
            this.mainArea = itemView.findViewById(R.id.row_main);
            this.title = itemView.findViewById(R.id.row_title);
            this.sub = itemView.findViewById(R.id.row_subtitle);
            this.time = itemView.findViewById(R.id.row_time);
            this.delete = itemView.findViewById(R.id.row_delete);
        }
    }

    private final class RowAdapter extends BaseAdapter {
        private List<HistoryRows.Row> rows = new ArrayList<>();
        /** Called when the X on a page line is tapped. */
        private Consumer<HistoryStore.Entry> onRemove;
        private Consumer<HistoryStore.Entry> onOpen;
        private Consumer<HistoryStore.Entry> onLongPress;

        void set(List<HistoryRows.Row> newRows) {
            rows = new ArrayList<>(newRows);
            notifyDataSetChanged();
        }

        void removeUrl(String url, boolean updateSiteCount) {
            if (url == null || rows.isEmpty()) return;
            final List<HistoryRows.Row> out = new ArrayList<>(rows.size());
            int sectionHeaderIdx = -1;
            int sectionPages = 0;
            for (int i = 0; i < rows.size(); i++) {
                final HistoryRows.Row r = rows.get(i);
                if (r.isHeader()) {
                    if (sectionHeaderIdx >= 0) {
                        if (sectionPages == 0) {
                            out.remove(sectionHeaderIdx);
                        } else if (updateSiteCount) {
                            out.set(sectionHeaderIdx, rewriteSiteHeader(out.get(sectionHeaderIdx), sectionPages));
                        }
                    }
                    sectionHeaderIdx = out.size();
                    sectionPages = 0;
                    out.add(r);
                } else if (url.equals(r.entry.url)) {
                    // Drop removed page.
                } else {
                    sectionPages++;
                    out.add(r);
                }
            }
            if (sectionHeaderIdx >= 0) {
                if (sectionPages == 0) {
                    out.remove(sectionHeaderIdx);
                } else if (updateSiteCount) {
                    out.set(sectionHeaderIdx, rewriteSiteHeader(out.get(sectionHeaderIdx), sectionPages));
                }
            }
            rows = out;
            notifyDataSetChanged();
        }

        private HistoryRows.Row rewriteSiteHeader(HistoryRows.Row oldHeader, int count) {
            final int sep = oldHeader.header.lastIndexOf("  \u00B7  ");
            if (sep < 0) return oldHeader;
            return HistoryRows.Row.heading(oldHeader.header.substring(0, sep) + "  \u00B7  " + count);
        }

        void setOnRemove(Consumer<HistoryStore.Entry> onRemove) {
            this.onRemove = onRemove;
        }

        void setOnOpen(Consumer<HistoryStore.Entry> onOpen) {
            this.onOpen = onOpen;
        }

        void setOnLongPress(Consumer<HistoryStore.Entry> onLongPress) {
            this.onLongPress = onLongPress;
        }

        @Override public int getCount() { return rows.size(); }
        @Override public HistoryRows.Row getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }

        // Two kinds of line. Headings are not selectable, so a tap on one does nothing.
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p).isHeader() ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return !rows.get(p).isHeader(); }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            final HistoryRows.Row row = rows.get(pos);
            final boolean header = row.isHeader();
            View v = cv;
            RowViewHolder holder = v != null && v.getTag() instanceof RowViewHolder
                    ? (RowViewHolder) v.getTag() : null;
            if (holder == null || holder.isHeader != header) {
                v = LayoutInflater.from(activity).inflate(
                        header ? R.layout.row_history_header : R.layout.row_history, parent, false);
                holder = header ? new RowViewHolder(v) : new RowViewHolder(v, false);
                if (!header) {
                    final RowViewHolder h = holder;
                    if (holder.mainArea != null) {
                        holder.mainArea.setOnClickListener(x -> {
                            if (onOpen != null && h.boundEntry != null) {
                                onOpen.accept(h.boundEntry);
                            }
                        });
                        holder.mainArea.setOnLongClickListener(x -> {
                            if (onLongPress != null && h.boundEntry != null) {
                                x.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                onLongPress.accept(h.boundEntry);
                                return true;
                            }
                            return false;
                        });
                    }
                    holder.delete.setOnClickListener(x -> {
                        if (onRemove != null && h.boundEntry != null) {
                            onRemove.accept(h.boundEntry);
                        }
                    });
                }
                v.setTag(holder);
            }
            if (header) {
                holder.headerText.setText(row.header);
            } else {
                final HistoryStore.Entry e = row.entry;
                holder.boundEntry = e;
                final String shown = SuggestionRanker.shortUrl(e.url);
                holder.title.setText(e.title == null || e.title.trim().isEmpty() ? shown : e.title);
                holder.sub.setText(shown);
                holder.time.setText(row.time);
            }
            return v;
        }
    }
}
