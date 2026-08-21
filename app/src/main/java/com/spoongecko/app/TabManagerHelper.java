package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
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
import java.util.Map;

public class TabManagerHelper {

    public interface TabActionListener {
        void onTabSelected(int index);
        void onTabClosed(int index);
        void onNewTab();
    }

    public static void show(Context context, List<GeckoSession> sessions,
                            Map<GeckoSession, String> titles, int currentIndex,
                            TabActionListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.tabs_title);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);
        container.setBackgroundColor(context.getResources().getColor(R.color.md_theme_surface, null));

        GridView grid = new GridView(context);
        grid.setNumColumns(2);
        grid.setPadding(0, 0, 0, 8);
        grid.setVerticalSpacing(8);
        grid.setHorizontalSpacing(8);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        final int[] lastClosedPosition = {-1};
        grid.setAdapter(new TabAdapter(context, sessions, titles, currentIndex, listener, lastClosedPosition));

        MaterialButton btnNewTab = new MaterialButton(context);
        btnNewTab.setText(R.string.new_tab_button);
        btnNewTab.setTextSize(16);
        btnNewTab.setPadding(0, 16, 0, 16);

        container.addView(grid);
        container.addView(btnNewTab);

        builder.setView(container);

        AlertDialog dialog = builder.create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position != lastClosedPosition[0]) {
                listener.onTabSelected(position);
                dialog.dismiss();
            }
            lastClosedPosition[0] = -1;
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
        private final Map<GeckoSession, String> titles;
        private final int currentIndex;
        private final TabActionListener listener;
        private final int[] lastClosedPosition;

        TabAdapter(Context context, List<GeckoSession> sessions,
                   Map<GeckoSession, String> titles, int currentIndex,
                   TabActionListener listener, int[] lastClosedPosition) {
            this.context = context;
            this.sessions = sessions;
            this.titles = titles;
            this.currentIndex = currentIndex;
            this.listener = listener;
            this.lastClosedPosition = lastClosedPosition;
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
                        GridView.LayoutParams.MATCH_PARENT, 160));
                cell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                cell.setBackgroundColor(context.getResources().getColor(R.color.md_theme_surface_variant, null));

                label = new TextView(context);
                label.setGravity(Gravity.CENTER);
                label.setTextSize(13);
                label.setPadding(12, 12, 28, 12);
                label.setBackgroundColor(context.getResources().getColor(R.color.md_theme_surface, null));
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                labelParams.setMargins(4, 4, 4, 4);
                cell.addView(label, labelParams);

                closeBtn = new MaterialButton(context);
                closeBtn.setText(R.string.tab_close);
                closeBtn.setTextSize(12);
                closeBtn.setTextColor(Color.RED);
                closeBtn.setBackgroundColor(Color.TRANSPARENT);
                closeBtn.setPadding(0, 0, 0, 0);
                closeBtn.setMinWidth(0);
                closeBtn.setMinHeight(0);
                closeBtn.setInsetTop(0);
                closeBtn.setInsetBottom(0);
                closeBtn.setFocusable(false);
                closeBtn.setFocusableInTouchMode(false);
                FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(48, 48);
                btnParams.gravity = Gravity.TOP | Gravity.END;
                cell.addView(closeBtn, btnParams);
            } else {
                cell = (FrameLayout) convertView;
                label = (TextView) cell.getChildAt(0);
                closeBtn = (MaterialButton) cell.getChildAt(1);
            }

            GeckoSession session = sessions.get(pos);
            String title = titles.get(session);
            if (title == null || title.trim().isEmpty()) {
                title = context.getString(R.string.tab_default_title, pos + 1);
            }
            label.setText(title);

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
                    lastClosedPosition[0] = position;
                    listener.onTabClosed(position);
                    v.post(() -> notifyDataSetChanged());
                }
            });

            return cell;
        }
    }
}
