package com.spoongecko.app;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import java.util.ArrayList;
import java.util.List;

class SuggestionAdapter extends ArrayAdapter<String> {

    private final List<String> values = new ArrayList<>();
    private final Context context;

    SuggestionAdapter(Context context) {
        super(context, android.R.layout.simple_dropdown_item_1line);
        this.context = context;
    }

    @Override
    public int getCount() {
        return values.size();
    }

    @Override
    public String getItem(int position) {
        return values.get(position);
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                if (constraint == null || constraint.length() == 0) {
                    results.values = new ArrayList<String>();
                    results.count = 0;
                    return results;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}

                List<HistoryStore.Entry> entries = HistoryStore.query(
                        context, constraint.toString().trim(), 10);
                List<String> urls = new ArrayList<>();
                for (HistoryStore.Entry entry : entries) {
                    if (entry.url != null && !entry.url.isEmpty()) {
                        urls.add(entry.url);
                    }
                }
                results.values = urls;
                results.count = urls.size();
                return results;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                values.clear();
                if (results.values != null) {
                    values.addAll((List<String>) results.values);
                }
                notifyDataSetChanged();
            }
        };
    }
}
