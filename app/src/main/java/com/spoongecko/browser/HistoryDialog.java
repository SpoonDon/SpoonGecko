package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

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
    private static final String PREFS_NAME = "spoongecko_prefs";
    private static final String PREF_SORT = "history_sort";

    private final Activity activity;
    private final HistoryStore history;
    private Dialog dialog;

    HistoryDialog(Activity activity, HistoryStore history) {
        this.activity = activity;
        this.history = history;
    }

    void show(final Consumer<String> onNavigate) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;

        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_history);
        final Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
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

        final Runnable load = () -> {
            if (load(current[0], modes, chips, adapter, list, empty, removed)) list.setSelection(0);
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

        list.setOnItemClickListener((parent, view, position, id) -> {
            final HistoryRows.Row row = adapter.getItem(position);
            if (row.entry == null) return;       // a section heading
            onNavigate.accept(row.entry.url);
            d.dismiss();
        });

        // The X on a page line: remove that page only, and leave the list where it was (the
        // user is usually clearing several pages in a row). A day or site heading whose last
        // page has gone disappears with it, because the rows are built again from what is left.
        adapter.setOnRemove(entry -> {
            removed.add(entry.url);
            history.remove(entry.url);
            final int first = list.getFirstVisiblePosition();
            final View firstRow = list.getChildAt(0);
            final int offset = firstRow == null ? 0 : firstRow.getTop();
            if (load(current[0], modes, chips, adapter, list, empty, removed)
                    && adapter.getCount() > 0) {
                list.setSelectionFromTop(Math.min(first, adapter.getCount() - 1), offset);
            }
        });

        final TextView neg = d.findViewById(R.id.dlg_negative);
        final TextView pos = d.findViewById(R.id.dlg_positive);
        neg.setOnClickListener(v -> d.dismiss());
        pos.setOnClickListener(v -> {
            try {
                history.clear();
            } catch (RuntimeException e) {
                Log.w(TAG, "history could not be cleared", e);
            }
            d.dismiss();
        });

        d.setOnDismissListener(x -> {
            if (dialog == d) dialog = null;
        });
        dialog = d;
        load.run();
        d.show();
    }

    /** Closes the dialog if it is showing (called when the activity is destroyed). */
    void dismiss() {
        final Dialog d = dialog;
        dialog = null;
        if (d != null) {
            try { d.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Reads the history and fills the list.
     *
     * @return true when there is something to show. Where the list is scrolled to afterwards is
     *         up to the caller: back to the top when the order changes, unchanged after a removal.
     */
    private boolean load(HistoryRows.Mode mode, HistoryRows.Mode[] modes, TextView[] chips,
                         RowAdapter adapter, ListView list, TextView empty, Set<String> removed) {
        List<HistoryStore.Entry> source;
        try {
            source = mode == HistoryRows.Mode.OLDEST ? history.oldest(LIMIT) : history.recent(LIMIT);
        } catch (RuntimeException e) {
            Log.w(TAG, "history could not be read", e);
            source = new ArrayList<>();
        }
        if (!removed.isEmpty()) {
            final List<HistoryStore.Entry> kept = new ArrayList<>();
            for (HistoryStore.Entry e : source) {
                if (e == null || e.url == null || !removed.contains(e.url)) kept.add(e);
            }
            source = kept;
        }
        final List<HistoryRows.Row> rows = HistoryRows.build(source, mode,
                System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault());
        adapter.set(rows);

        for (int i = 0; i < chips.length; i++) {
            final boolean selected = modes[i] == mode;
            chips[i].setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chips[i].setTextColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
        }

        final boolean none = rows.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        if (!none) fitListHeight(list, adapter);
        return !none;
    }

    /**
     * ListView ignores android:maxHeight, so the height is measured from the rows and capped
     * here (the same approach as BrowserDialogs.capListHeight). Done again whenever the order
     * changes, because headings change how tall the list is.
     */
    private void fitListHeight(ListView list, RowAdapter adapter) {
        final int max = dp(LIST_MAX_DP);
        int total = 0;
        for (int i = 0; i < adapter.getCount() && total < max; i++) {
            final View row = adapter.getView(i, null, list);
            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            total += row.getMeasuredHeight();
        }
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = total == 0 ? max : Math.min(total, max);
        list.setLayoutParams(lp);
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

    private final class RowAdapter extends BaseAdapter {
        private List<HistoryRows.Row> rows = new ArrayList<>();
        /** Called when the X on a page line is tapped. */
        private Consumer<HistoryStore.Entry> onRemove;

        void set(List<HistoryRows.Row> newRows) {
            rows = newRows;
            notifyDataSetChanged();
        }

        void setOnRemove(Consumer<HistoryStore.Entry> onRemove) {
            this.onRemove = onRemove;
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
            // A view of the other kind (possible when the list height is measured) is not reused.
            if (v == null || !Boolean.valueOf(header).equals(v.getTag())) {
                v = LayoutInflater.from(activity).inflate(
                        header ? R.layout.row_history_header : R.layout.row_history, parent, false);
                v.setTag(Boolean.valueOf(header));
            }
            if (header) {
                ((TextView) v).setText(row.header);
            } else {
                final HistoryStore.Entry e = row.entry;
                final String shown = SuggestionRanker.shortUrl(e.url);
                final TextView title = v.findViewById(R.id.row_title);
                final TextView sub = v.findViewById(R.id.row_subtitle);
                final TextView time = v.findViewById(R.id.row_time);
                title.setText(e.title == null || e.title.trim().isEmpty() ? shown : e.title);
                sub.setText(shown);
                time.setText(row.time);
                // Set again every time the view is bound: a recycled row must remove ITS page.
                v.findViewById(R.id.row_delete).setOnClickListener(x -> {
                    if (onRemove != null) onRemove.accept(e);
                });
            }
            return v;
        }
    }
}
