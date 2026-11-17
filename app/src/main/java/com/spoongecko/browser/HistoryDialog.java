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
import java.util.List;
import java.util.Locale;
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

        final RowAdapter adapter = new RowAdapter();
        list.setAdapter(adapter);

        final Runnable load = () -> load(current[0], modes, chips, adapter, list, empty);
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

    private void load(HistoryRows.Mode mode, HistoryRows.Mode[] modes, TextView[] chips,
                      RowAdapter adapter, ListView list, TextView empty) {
        List<HistoryStore.Entry> source;
        try {
            source = mode == HistoryRows.Mode.OLDEST ? history.oldest(LIMIT) : history.recent(LIMIT);
        } catch (RuntimeException e) {
            Log.w(TAG, "history could not be read", e);
            source = new ArrayList<>();
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
        if (!none) {
            fitListHeight(list, adapter);
            list.setSelection(0);
        }
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

        void set(List<HistoryRows.Row> newRows) {
            rows = newRows;
            notifyDataSetChanged();
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
            }
            return v;
        }
    }
}
