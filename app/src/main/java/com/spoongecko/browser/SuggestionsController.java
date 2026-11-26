package com.spoongecko.browser;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owns the URL-bar TextWatcher, 150 ms debounce, local history matching, real-time DuckDuckGo
 * search predictions, and the suggestion dropdown adapter.
 */
public final class SuggestionsController {

    private static final int MAX_TOTAL_SUGGESTIONS = 8;
    private static final int MAX_REMOTE_SUGGESTIONS = 6;
    private static final int MAX_HISTORY_WHEN_MERGED = 4;

    public interface Listener {
        void onSuggestionPicked(String url);
    }

    private static final class SuggestionItem {
        final boolean isSearch;
        final String title;
        final String subtitle;
        final String targetUrl;

        SuggestionItem(boolean isSearch, String title, String subtitle, String targetUrl) {
            this.isSearch = isSearch;
            this.title = title;
            this.subtitle = subtitle;
            this.targetUrl = targetUrl;
        }
    }

    private final Activity activity;
    private final EditText urlBar;
    private final ListView list;
    private final HistoryStore history;
    private final SearchSuggestionsClient searchClient;
    private final Adapter adapter;
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private final int maxListHeightPx;

    private Runnable pending;
    private boolean suppress = false;
    private Listener listener;

    /**
     * Bumped by every new query AND by hide()/dispose(), so an answer still in flight when
     * the dropdown is closed (Enter pressed, page navigated, activity destroyed) cannot
     * re-open it afterwards. UI thread only.
     */
    private int generation = 0;

    public SuggestionsController(Activity activity, EditText urlBar, ListView list,
                                 HistoryStore history) {
        this.activity = activity;
        this.urlBar = urlBar;
        this.list = list;
        this.history = history;
        this.searchClient = new SearchSuggestionsClient();
        this.adapter = new Adapter(activity);
        this.maxListHeightPx = Math.round(300f * activity.getResources().getDisplayMetrics().density);

        list.getLayoutParams().height = maxListHeightPx;
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            final SuggestionItem item = adapter.getItem(pos);
            setText(item.isSearch ? item.title : item.targetUrl);
            if (listener != null) listener.onSuggestionPicked(item.targetUrl);
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
                if (q.length() < 1 || q.length() > SuggestionRanker.MAX_QUERY_CHARS) {
                    hide();
                    return;
                }
                pending = () -> runQuery(q);
                debounce.postDelayed(pending, 150);
            }
        });

        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) hide();
        });
    }

    private void runQuery(final String q) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (!urlBar.hasFocus()) return;
        if (!urlBar.getText().toString().trim().equals(q)) return;

        final int token = ++generation;
        final boolean fetchRemote = BrowserSettings.isSearchSuggestionsEnabled(activity)
                && SearchSuggestionsClient.shouldFetchRemote(q);

        final List<HistoryStore.Entry> historyHolder = new ArrayList<>();
        final List<String> remoteHolder = new ArrayList<>();
        final boolean[] done = {false, !fetchRemote};   // [historyDone, remoteDone]

        final Runnable publish = () -> {
            if (token != generation) return;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (!urlBar.hasFocus() || !urlBar.getText().toString().trim().equals(q)) return;

            final List<SuggestionItem> merged = buildMerged(historyHolder, remoteHolder);
            if (merged.isEmpty()) {
                if (done[0] && done[1]) hide();
                return;
            }
            adapter.setData(merged);
            fitListHeight();
            list.setVisibility(View.VISIBLE);
        };

        history.suggestAsync(q, MAX_TOTAL_SUGGESTIONS, results -> {
            if (token != generation) return;
            historyHolder.clear();
            if (results != null) historyHolder.addAll(results);
            done[0] = true;
            publish.run();
        });

        if (fetchRemote) {
            searchClient.fetchAsync(q, MAX_REMOTE_SUGGESTIONS, predictions -> {
                if (token != generation) return;
                remoteHolder.clear();
                if (predictions != null) remoteHolder.addAll(predictions);
                done[1] = true;
                publish.run();
            });
        } else {
            searchClient.cancelPending();
        }
    }

    private List<SuggestionItem> buildMerged(List<HistoryStore.Entry> historyEntries,
                                               List<String> remoteQueries) {
        final List<SuggestionItem> out = new ArrayList<>(MAX_TOTAL_SUGGESTIONS);
        final int historyCap = remoteQueries.isEmpty()
                ? MAX_TOTAL_SUGGESTIONS : MAX_HISTORY_WHEN_MERGED;

        final Set<String> seenTitles = new HashSet<>();
        for (int i = 0; i < historyEntries.size() && out.size() < historyCap; i++) {
            final HistoryStore.Entry e = historyEntries.get(i);
            if (e == null || e.url == null || e.url.isEmpty()) continue;
            final String shown = SuggestionRanker.shortUrl(e.url);
            final String title = (e.title == null || e.title.trim().isEmpty()) ? shown : e.title.trim();
            seenTitles.add(title.toLowerCase(Locale.ROOT));
            out.add(new SuggestionItem(false, title, shown, e.url));
        }

        final String engineLabel = BrowserSettings.getSearchEngine(activity).displayName;
        for (String pred : remoteQueries) {
            if (out.size() >= MAX_TOTAL_SUGGESTIONS) break;
            if (pred == null) continue;
            final String clean = pred.trim();
            if (clean.isEmpty()) continue;
            if (seenTitles.add(clean.toLowerCase(Locale.ROOT))) {
                out.add(new SuggestionItem(
                        true,
                        clean,
                        "Search with " + engineLabel,
                        BrowserSettings.searchUrlForQuery(activity, clean)));
            }
        }
        return out;
    }

    private void fitListHeight() {
        final int count = adapter.getCount();
        if (count <= 0) return;
        int total = 0;
        View rowView = null;
        final int widthSpec = View.MeasureSpec.makeMeasureSpec(
                Math.max(0, list.getWidth()),
                list.getWidth() > 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < count && total < maxListHeightPx; i++) {
            rowView = adapter.getView(i, rowView, list);
            rowView.measure(widthSpec, View.MeasureSpec.UNSPECIFIED);
            total += rowView.getMeasuredHeight();
        }
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        final int target = total == 0 ? maxListHeightPx : Math.min(total, maxListHeightPx);
        if (lp.height != target) {
            lp.height = target;
            list.setLayoutParams(lp);
        }
    }

    public void setListener(Listener l) { listener = l; }

    public void hide() {
        generation++;   // invalidate any query still in flight
        searchClient.cancelPending();
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
        searchClient.shutdown();
    }

    private static final class ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView sub;

        ViewHolder(View v) {
            this.icon = v.findViewById(R.id.row_icon);
            this.title = v.findViewById(R.id.row_title);
            this.sub = v.findViewById(R.id.row_subtitle);
        }
    }

    private static final class Adapter extends BaseAdapter {
        private final Activity activity;
        private List<SuggestionItem> data = Collections.emptyList();

        Adapter(Activity activity) { this.activity = activity; }

        void setData(List<SuggestionItem> d) {
            data = d != null ? d : Collections.emptyList();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public SuggestionItem getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            final View v;
            final ViewHolder h;
            if (cv == null) {
                v = activity.getLayoutInflater().inflate(R.layout.row_suggestion, parent, false);
                h = new ViewHolder(v);
                v.setTag(h);
            } else {
                v = cv;
                h = (ViewHolder) v.getTag();
            }
            final SuggestionItem item = data.get(pos);
            h.icon.setImageResource(item.isSearch ? R.drawable.ic_search : R.drawable.ic_history);
            h.title.setText(item.title);
            h.sub.setText(item.subtitle);
            return v;
        }
    }
}
