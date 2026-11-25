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
 *
 * [PERF] The history query used to run on the UI thread; it now runs on a
 * background thread (HistoryStore.suggestAsync) and its result is applied
 * only if it is still the latest answer to what is in the URL bar.
 *
 * [SUGGEST] What is matched and in which order is decided by HistoryStore.suggest() with
 * SuggestionRanker (several words, better matches first, one line per page). This class also
 * ignores text that is too long to be a search (a pasted address) and no longer runs a query
 * that was still waiting out its 150 ms when the URL bar lost focus (Enter pressed).
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

    /**
     * Bumped by every new query AND by hide()/dispose(), so an answer that is
     * still in flight when the dropdown is closed (Enter pressed, page
     * navigated, activity destroyed, ...) can never re-open it afterwards.
     * UI thread only.
     */
    private int generation = 0;

    public SuggestionsController(Activity activity, EditText urlBar, ListView list,
                                 HistoryStore history) {
        this.urlBar = urlBar;
        this.list = list;
        this.history = history;
        this.adapter = new Adapter(activity);

        // [L] ListView silently ignores android:maxHeight="300dp" declared in
        // activity_main.xml; apply the intended cap programmatically.
        list.getLayoutParams().height =
                Math.round(300f * activity.getResources().getDisplayMetrics().density);

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
                // [SUGGEST] Text this long is a pasted address, not a search: nothing to look up.
                if (q.length() < 1 || q.length() > SuggestionRanker.MAX_QUERY_CHARS) {
                    hide();
                    return;
                }
                pending = () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    // [SUGGEST] The bar lost focus while this was waiting (Enter was pressed):
                    // the dropdown must not open again behind the page that is loading.
                    if (!urlBar.hasFocus()) return;
                    if (!urlBar.getText().toString().trim().equals(q)) return;
                    final int token = ++generation;
                    history.suggestAsync(q, 8, results -> {
                        // UI thread. Drop the answer if anything changed since
                        // the query started.
                        if (token != generation) return;
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (!urlBar.getText().toString().trim().equals(q)) return;
                        if (results.isEmpty()) { hide(); return; }
                        adapter.setData(results);
                        list.setVisibility(View.VISIBLE);
                    });
                };
                debounce.postDelayed(pending, 150);
            }
        });

        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) hide();
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void hide() {
        generation++;   // invalidate any query still in flight
        list.setVisibility(View.GONE);
    }

    // [M1] Lets the back handler close the dropdown before page history.
    public boolean isVisible() { return list.getVisibility() == View.VISIBLE; }

    /** Sets URL-bar text without triggering the suggestion query. */
    public void setText(String text) {
        suppress = true;
        urlBar.setText(text);
        suppress = false;
    }

    public void dispose() {
        generation++;
        if (pending != null) {
            debounce.removeCallbacks(pending);
            pending = null;
        }
    }

    private static final class ViewHolder {
        final TextView title;
        final TextView sub;

        ViewHolder(View v) {
            this.title = v.findViewById(R.id.row_title);
            this.sub = v.findViewById(R.id.row_subtitle);
            this.title.setTextColor(0xFFFFFFFF);
            this.sub.setTextColor(0x66FFFFFF);
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
            final View v;
            final ViewHolder h;
            if (cv == null) {
                v = activity.getLayoutInflater().inflate(R.layout.row_item, parent, false);
                h = new ViewHolder(v);
                v.setTag(h);
            } else {
                v = cv;
                h = (ViewHolder) v.getTag();
            }
            HistoryStore.Entry e = data.get(pos);
            // [SUGGEST] The address is shown without "https://", "www." and a trailing "/", so
            // more of it fits on the line and pages saved under several spellings read alike.
            // Picking a row still opens the full address.
            final String shown = SuggestionRanker.shortUrl(e.url);
            h.title.setText(e.title == null || e.title.trim().isEmpty() ? shown : e.title);
            h.sub.setText(shown);
            return v;
        }
    }
}
