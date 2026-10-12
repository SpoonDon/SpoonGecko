package com.spoongecko.browser;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

public class TabAdapter extends BaseAdapter {

    public interface Callbacks {
        void onOpen(int index);
        void onClose(int index);
    }

    private static final int BG_CURRENT = 0xFF2C2C2E;
    private static final int BG_OTHER   = 0xFF1A1A1A;

    private final Context ctx;
    private final TabManager mgr;
    private final Callbacks cb;

    public TabAdapter(Context ctx, TabManager mgr, Callbacks cb) {
        this.ctx = ctx;
        this.mgr = mgr;
        this.cb = cb;
    }

    @Override public int getCount() { return mgr.getTabCount(); }
    @Override public Object getItem(int i) { return mgr.getTabs().get(i); }
    @Override public long getItemId(int i) { return mgr.getTabs().get(i).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_tab, parent, false);
        }

        Tab tab = mgr.getTabs().get(position);

        TextView title = v.findViewById(R.id.tab_title);
        TextView url = v.findViewById(R.id.tab_url);
        TextView close = v.findViewById(R.id.tab_close);
        View card = v.findViewById(R.id.tab_card);

        title.setText(tab.displayTitle());
        url.setText((tab.url == null || tab.url.isEmpty()) ? "about:blank" : tab.url);
        card.setBackgroundColor(position == mgr.getCurrentIndex() ? BG_CURRENT : BG_OTHER);

        final int pos = position;
        v.setOnClickListener(view -> cb.onOpen(pos));
        close.setOnClickListener(view -> cb.onClose(pos));
        return v;
    }
}
