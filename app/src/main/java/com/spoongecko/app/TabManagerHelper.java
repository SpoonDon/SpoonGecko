package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoSession;

import java.util.List;

public class TabManagerHelper {

    public interface TabActionListener {
        void onTabSelected(int index);
        void onTabClosed(int index);
    }

    public static void show(Context context, List<GeckoSession> sessions,
                            int currentIndex, TabActionListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Tabs");

        GridView grid = new GridView(context);
        grid.setNumColumns(3);
        grid.setPadding(8, 8, 8, 8);
        grid.setVerticalSpacing(8);
        grid.setHorizontalSpacing(8);
        grid.setAdapter(new TabAdapter(sessions, currentIndex));

        builder.setView(grid);

        AlertDialog dialog = builder.create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            listener.onTabSelected(position);
            dialog.dismiss();
        });

        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (sessions.size() > 1) {
                listener.onTabClosed(position);
                ((TabAdapter) grid.getAdapter()).notifyDataSetChanged();
            }
            return true;
        });

        dialog.show();
    }

    private static class TabAdapter extends BaseAdapter {
        private final List<GeckoSession> sessions;
        private final int currentIndex;

        TabAdapter(List<GeckoSession> sessions, int currentIndex) {
            this.sessions = sessions;
            this.currentIndex = currentIndex;
        }

        @Override public int getCount() { return sessions.size(); }
        @Override public Object getItem(int pos) { return sessions.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView == null) {
                tv = new TextView(parent.getContext());
                tv.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, 120));
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(14);
                tv.setPadding(4, 4, 4, 4);
                tv.setBackgroundResource(android.R.drawable.editbox_background);
            } else {
                tv = (TextView) convertView;
            }

            tv.setText("Tab " + (pos + 1));
            if (pos == currentIndex) {
                tv.setTextColor(parent.getContext().getResources()
                        .getColor(R.color.md_theme_primary, null));
            } else {
                tv.setTextColor(parent.getContext().getResources()
                        .getColor(R.color.md_theme_on_surface, null));
            }
            return tv;
        }
    }
}
