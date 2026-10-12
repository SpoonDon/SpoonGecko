package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * History browser: searchable, sortable, per-row context menu (open, open in
 * new tab, copy, share, edit, delete), and a global Clear-all action.
 *
 * Uses an AlertDialog with a custom view. Long-press on a row opens a
 * PopupMenu; tap navigates in the current tab.
 */
public final class HistoryDialog {

    public interface Callbacks {
        void onNavigate(String url);
        void onOpenInNewTab(String url);
    }

    private HistoryDialog() {}

    public static void show(final Activity activity, final Callbacks cb) {
        final List<HistoryStore.Entry> initial = HistoryStore.load(activity);
        if (initial.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("History")
                    .setMessage("No history yet.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        root.setPadding(pad, pad, pad, 0);

        // Top row: search + sort spinner
        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText search = new EditText(activity);
        search.setHint("Search title or URL");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(search, searchLp);

        final Spinner sortSpinner = new Spinner(activity);
        ArrayAdapter<HistoryStore.Sort> sortAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item,
                HistoryStore.Sort.values());
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(sortAdapter);
        sortSpinner.setSelection(0);
        topRow.addView(sortSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(topRow);

        final ListView list = new ListView(activity);
        list.setDividerHeight(0);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Adapter adapter = new Adapter(activity, initial);
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.setQuery(s == null ? "" : s.toString().trim());
            }
        });

        sortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                adapter.setSort(HistoryStore.Sort.values()[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            HistoryStore.Entry e = adapter.getItemAt(pos);
            if (e == null) return;
            cb.onNavigate(e.url);
        });

        list.setOnItemLongClickListener((p, v, pos, id) -> {
            HistoryStore.Entry e = adapter.getItemAt(pos);
            if (e == null) return true;
            showContextMenu(activity, v, e, cb, adapter);
            return true;
        });

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("History")
                .setView(root)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear all", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    confirmClearAll(activity, adapter));
        });
        dialog.show();
    }

    // -------------------------------------------------------------- helpers

    private static void showContextMenu(final Activity activity, View anchor,
                                        final HistoryStore.Entry entry,
                                        final Callbacks cb,
                                        final Adapter adapter) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 2, 1, "Open in new tab");
        menu.getMenu().add(0, 3, 2, "Copy URL");
        menu.getMenu().add(0, 4, 3, "Share");
        menu.getMenu().add(0, 5, 4, "Edit");
        menu.getMenu().add(0, 6, 5, "Delete");

        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    cb.onNavigate(entry.url);
                    return true;
                case 2:
                    cb.onOpenInNewTab(entry.url);
                    return true;
                case 3: {
                    ClipboardManager cm = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("url", entry.url));
                        Toast.makeText(activity, R.string.copied,
                                Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                case 4: {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, entry.url);
                    activity.startActivity(
                            Intent.createChooser(i, "Share"));
                    return true;
                }
                case 5:
                    showEditDialog(activity, entry, adapter);
                    return true;
                case 6:
                    HistoryStore.delete(activity, entry);
                    adapter.removeEntry(entry);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private static void showEditDialog(final Activity activity,
                                       final HistoryStore.Entry entry,
                                       final Adapter adapter) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, pad, pad, pad);

        final EditText titleField = new EditText(activity);
        titleField.setHint("Title");
        titleField.setText(entry.title);
        titleField.setSingleLine(true);
        root.addView(titleField);

        final EditText urlField = new EditText(activity);
        urlField.setHint("URL");
        urlField.setText(entry.url);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlField);

        new AlertDialog.Builder(activity)
                .setTitle("Edit entry")
                .setView(root)
                .setPositiveButton("Save", (d, w) -> {
                    String t = titleField.getText().toString().trim();
                    String u = urlField.getText().toString().trim();
                    HistoryStore.update(activity, entry, t, u);
                    adapter.reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmClearAll(final Activity activity,
                                        final Adapter adapter) {
        new AlertDialog.Builder(activity)
                .setTitle("Clear history")
                .setMessage("Delete all history entries? This cannot be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    HistoryStore.clear(activity);
                    adapter.reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------- adapter

    private static final class Adapter extends BaseAdapter {
        private final Activity activity;
        private final List<HistoryStore.Entry> source = new ArrayList<>();
        private final List<HistoryStore.Entry> visible = new ArrayList<>();
        private String query = "";
        private HistoryStore.Sort sort = HistoryStore.Sort.DATE_DESC;
        private final SimpleDateFormat dateFmt =
                new SimpleDateFormat("MMM d, HH:mm", Locale.US);

        Adapter(Activity activity, List<HistoryStore.Entry> entries) {
            this.activity = activity;
            this.source.addAll(entries);
            rebuild();
        }

        private void rebuild() {
            visible.clear();
            String q = query == null ? "" : query.toLowerCase(Locale.US);
            for (HistoryStore.Entry e : source) {
                if (q.isEmpty()
                        || e.url.toLowerCase(Locale.US).contains(q)
                        || e.title.toLowerCase(Locale.US).contains(q)) {
                    visible.add(e);
                }
            }
            HistoryStore.sort(visible, sort);
            notifyDataSetChanged();
        }

        void setQuery(String q) { this.query = q == null ? "" : q; rebuild(); }
        void setSort(HistoryStore.Sort s) {
            this.sort = s == null ? HistoryStore.Sort.DATE_DESC : s;
            rebuild();
        }
        void reload() {
            source.clear();
            source.addAll(HistoryStore.load(activity));
            rebuild();
        }
        void removeEntry(HistoryStore.Entry e) {
            source.remove(e);
            rebuild();
        }

        HistoryStore.Entry getItemAt(int i) {
            if (i < 0 || i >= visible.size()) return null;
            return visible.get(i);
        }

        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int i) { return visible.get(i); }
        @Override public long getItemId(int i) { return visible.get(i).timestamp; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            Holder h;
            if (convertView == null) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                int p = dp(activity, 12);
                row.setPadding(p, p, p, p);

                TextView title = new TextView(activity);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);

                TextView url = new TextView(activity);
                url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                url.setSingleLine(true);
                url.setEllipsize(TextUtils.TruncateAt.END);
                url.setTextColor(0xFF9A9AA2);
                row.addView(url);

                TextView meta = new TextView(activity);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                meta.setTextColor(0xFF6A6A72);
                row.addView(meta);

                h = new Holder();
                h.title = title; h.url = url; h.meta = meta;
                row.setTag(h);
            } else {
                row = (LinearLayout) convertView;
                h = (Holder) row.getTag();
            }

            HistoryStore.Entry e = visible.get(position);
            h.title.setText(e.displayTitle());
            h.url.setText(e.url);
            String visits = e.visits > 1 ? "  ·  " + e.visits + " visits" : "";
            h.meta.setText(dateFmt.format(new Date(e.timestamp)) + visits);

            return row;
        }

        private static final class Holder {
            TextView title, url, meta;
        }
    }
}
