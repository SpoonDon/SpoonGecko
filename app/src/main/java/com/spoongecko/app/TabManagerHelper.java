package com.spoongecko.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
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
        BottomSheetDialog dialog = new BottomSheetDialog(context);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UiUtils.dp(context, 16), UiUtils.dp(context, 8),
                UiUtils.dp(context, 16), UiUtils.dp(context, 16));
        container.setBackgroundColor(context.getResources().getColor(R.color.md_theme_surface, null));

        TextView header = new TextView(context);
        header.setText(R.string.tabs_title);
        header.setTextSize(18);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(context.getResources().getColor(R.color.md_theme_on_surface, null));
        header.setPadding(0, 0, 0, UiUtils.dp(context, 12));
        container.addView(header);

        RecyclerView grid = new RecyclerView(context);
        grid.setLayoutManager(new GridLayoutManager(context, 2));
        grid.setPadding(0, 0, 0, UiUtils.dp(context, 12));
        grid.setClipToPadding(false);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        grid.setAdapter(new TabAdapter(context, sessions, titles, currentIndex, listener, dialog));
        container.addView(grid);

        MaterialButton btnNewTab = new MaterialButton(context);
        btnNewTab.setText(R.string.new_tab_button);
        btnNewTab.setTextSize(16);
        btnNewTab.setPadding(0, UiUtils.dp(context, 12), 0, UiUtils.dp(context, 12));
        btnNewTab.setOnClickListener(v -> {
            listener.onNewTab();
            dialog.dismiss();
        });
        container.addView(btnNewTab);

        dialog.setContentView(container);
        dialog.setOnShowListener(di -> {
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            if (behavior != null) {
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
                behavior.setExpandedOffset(UiUtils.dp(context, 72));
            }
        });
        dialog.show();
    }

    private static class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabViewHolder> {

        private final Context context;
        private final List<GeckoSession> sessions;
        private final Map<GeckoSession, String> titles;
        private final int currentIndex;
        private final TabActionListener listener;
        private final BottomSheetDialog dialog;

        TabAdapter(Context context, List<GeckoSession> sessions,
                   Map<GeckoSession, String> titles, int currentIndex,
                   TabActionListener listener, BottomSheetDialog dialog) {
            this.context = context;
            this.sessions = sessions;
            this.titles = titles;
            this.currentIndex = currentIndex;
            this.listener = listener;
            this.dialog = dialog;
        }

        @Override
        public int getItemCount() {
            return sessions.size();
        }

        @NonNull
        @Override
        public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout cell = new FrameLayout(context);
            GridLayoutManager.LayoutParams cellParams = new GridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiUtils.dp(context, 140));
            cellParams.setMargins(UiUtils.dp(context, 4), UiUtils.dp(context, 4),
                    UiUtils.dp(context, 4), UiUtils.dp(context, 4));
            cell.setLayoutParams(cellParams);
            cell.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            cell.setBackgroundColor(context.getResources().getColor(R.color.md_theme_surface_variant, null));

            TextView label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(13);
            label.setPadding(UiUtils.dp(context, 12), UiUtils.dp(context, 12),
                    UiUtils.dp(context, 28), UiUtils.dp(context, 12));
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            labelParams.setMargins(UiUtils.dp(context, 4), UiUtils.dp(context, 4),
                    UiUtils.dp(context, 4), UiUtils.dp(context, 4));
            cell.addView(label, labelParams);

            MaterialButton closeBtn = new MaterialButton(context);
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
            FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
                    UiUtils.dp(context, 48), UiUtils.dp(context, 48));
            btnParams.gravity = Gravity.TOP | Gravity.END;
            cell.addView(closeBtn, btnParams);

            return new TabViewHolder(cell, label, closeBtn);
        }

        @Override
        public void onBindViewHolder(@NonNull TabViewHolder holder, final int position) {
            GeckoSession session = sessions.get(position);
            String title = titles.get(session);
            if (title == null || title.trim().isEmpty()) {
                title = context.getString(R.string.tab_default_title, position + 1);
            }
            holder.label.setText(title);

            if (position == currentIndex) {
                holder.label.setTextColor(context.getResources().getColor(R.color.md_theme_primary, null));
                holder.label.setTypeface(null, Typeface.BOLD);
            } else {
                holder.label.setTextColor(context.getResources().getColor(R.color.md_theme_on_surface, null));
                holder.label.setTypeface(null, Typeface.NORMAL);
            }

            holder.label.setOnClickListener(v -> {
                listener.onTabSelected(position);
                dialog.dismiss();
            });

            holder.closeBtn.setOnClickListener(v -> {
                if (sessions.size() > 1) {
                    listener.onTabClosed(position);
                    v.post(this::notifyDataSetChanged);
                }
            });
        }

        private static class TabViewHolder extends RecyclerView.ViewHolder {
            final TextView label;
            final MaterialButton closeBtn;

            TabViewHolder(View itemView, TextView label, MaterialButton closeBtn) {
                super(itemView);
                this.label = label;
                this.closeBtn = closeBtn;
            }
        }
    }
}
