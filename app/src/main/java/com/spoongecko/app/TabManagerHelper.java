package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import org.mozilla.geckoview.GeckoSession;

import java.util.List;

public class TabManagerHelper {

    public interface TabActionListener {
        void onTabSelected(int index);
        void onTabClosed(int index);
        void onNewTab();
    }

    public static void show(Context context, List<GeckoSession> sessions,
                            int currentIndex, TabActionListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Tabs");

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);

        GridView grid = new GridView(context);
        grid.setNumColumns(3);
        grid.setPadding(0, 0, 0, 8);
        grid.setVerticalSpacing(8);
        grid.setHorizontalSpacing(8);
        grid.setAdapter(new TabAdapter(context, sessions, currentIndex, listener));
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        MaterialButton btnNewTab = new MaterialButton(context);
        btnNewTab.setText("+ New Tab");
        btnNewTab.setTextSize(16);
        btnNewTab.setPadding(0, 16, 0, 16);

        container.addView(grid);
        container.addView(btnNewTab);

        builder.setView(container);

        AlertDialog dialog = builder.create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            listener.onTabSelected(position);
            dialog.dismiss();
        });

        btnNewTab.setOnClickListener(v -> {
            listener.onNewTab();
            dialog.dismiss();
        });

        dialog.show();
    }

    private static class TabAdapter extends BaseAdapter {
        private final Context context;
        private final List<GeckoSession> sessions;
        private final int currentIndex;
        private final TabActionListener listener;

        TabAdapter(Context context, List<GeckoSession> sessions, int currentIndex, TabActionListener listener) {
            this.context = context;
            this.sessions = sessions;
            this.currentIndex = currentIndex;
            this.listener = listener;
        }

        @Override public int getCount() { return sessions.size(); }
        @Override public Object getItem(int pos) { return sessions.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            FrameLayout cell;
            TextView label;
            MaterialButton closeBtn;

            if (convertView == null) {
                cell = new FrameLayout(context);
                cell.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, 120));

                label = new TextView(context);
                label.setGravity(Gravity.CENTER);
                label.setTextSize(14);
                label.setPadding(8, 8, 8, 8);
                label.setBackgroundResource(android.R.drawable.editbox_background);
                FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                cell.addView(label, labelParams);

                closeBtn = new MaterialButton(context);
                closeBtn.setText("\u2715");
                closeBtn.setTextSize(12);
                closeBtn.setTextColor(Color.RED);
                closeBtn.setBackgroundColor(Color.TRANSPARENT);
                closeBtn.setPadding(0, 0, 0, 0);
                closeBtn.setMinWidth(0);
                closeBtn.setMinHeight(0);
                closeBtn.setInsetTop(0);
                closeBtn.setInsetBottom(0);
                FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(48, 48);
                btnParams.gravity = Gravity.TOP | Gravity.END;
                cell.addView(closeBtn, btnParams);
            } else {
                cell = (FrameLayout) convertView;
                label = (TextView) cell.getChildAt(0);
                closeBtn = (MaterialButton) cell.getChildAt(1);
            }

            label.setText("Tab " + (pos + 1));
            if (pos == currentIndex) {
                label.setTextColor(context.getResources().getColor(R.color.md_theme_primary, null));
                label.setTypeface(null, Typeface.BOLD);
            } else {
                label.setTextColor(context.getResources().getColor(R.color.md_theme_on_surface, null));
                label.setTypeface(null, Typeface.NORMAL);
            }

            final int position = pos;
            closeBtn.setOnClickListener(v -> {
                if (sessions.size() > 1) {
                    listener.onTabClosed(position);
                    notifyDataSetChanged();
                }
            });

            return cell;
        }
    }
}
