package com.spoongecko.browser;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the URL-bar TextWatcher, 150 ms debounce, and the ListView adapter.
 * Behaviour matches the previous inline implementation.
 */
public final class SuggestionsController {

    public interface Listener {
        void onSuggestionPicked(String url);
    }

    private final EditText urlBar;
    private final ListView list;
    private final HistoryStore history;
    private final Adapter adapter;
    private final Handler debounce = new Handler(Looper.getMainLooper());

    private Runnable pending;
    private boolean suppress = false;
    private Listener listener;

    public SuggestionsController(Activity activity, EditText urlBar, ListView list,
                                 HistoryStore history) {
        this.urlBar = urlBar;
        this.list = list;
        this.history = history;
        this.adapter = new Adapter(activity);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            HistoryStore.Entry e = adapter.getItem(pos);
            setText(e.url);
            if (listener != null) listener.onSuggestionPicked(e.url);
            urlBar.clearFocus();
            hide();
        });

        urlBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppress) return;
                final String q = s.toString().trim();
                if (pending != null) debounce.removeCallbacks(pending);
                if (q.length() < 1) { hide(); return; }
                pending = () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (!urlBar.getText().toString().trim().equals(q)) return;
                    List<HistoryStore.Entry> results = history.suggest(q, 8);
                    if (results.isEmpty()) { hide(); return; }
                    adapter.setData(results);
                    list.setVisibility(View.VISIBLE);
                };
                debounce.postDelayed(pending, 150);
            }
        });

        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) hide();
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void hide() { list.setVisibility(View.GONE); }

    /** Sets URL-bar text without triggering the suggestion query. */
    public void setText(String text) {
        suppress = true;
        urlBar.setText(text);
        suppress = false;
    }

    public void dispose() {
        if (pending != null) {
            debounce.removeCallbacks(pending);
            pending = null;
        }
    }

    private static final class Adapter extends BaseAdapter {
        private final Activity activity;
        private List<HistoryStore.Entry> data = new ArrayList<>();

        Adapter(Activity activity) { this.activity = activity; }

        void setData(List<HistoryStore.Entry> d) { data = d; notifyDataSetChanged(); }

        @Override public int getCount() { return data.size(); }
        @Override public HistoryStore.Entry getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv != null ? cv
                    : activity.getLayoutInflater().inflate(R.layout.row_item, parent, false);
            HistoryStore.Entry e = data.get(pos);
            TextView title = v.findViewById(R.id.row_title);
            TextView sub = v.findViewById(R.id.row_subtitle);
            title.setText(e.title == null || e.title.isEmpty() ? e.url : e.title);
            title.setTextColor(0xFFFFFFFF);
            sub.setText(e.url);
            sub.setTextColor(0x66FFFFFF);
            return v;
        }
    }
}
