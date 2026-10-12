package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen tab switcher. Tap a card to switch; tap ✕ to close that tab;
 * "New tab" opens a blank tab.
 */
public final class TabSwitcherDialog {

    private TabSwitcherDialog() {}

    public static void show(Activity activity, TabManager mgr) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        int pad = dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(activity);
        header.setText("Tabs");
        header.setTextColor(0xFFEEEEEE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.setPadding(0, 0, 0, pad);
        root.addView(header);

        GridView grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(activity, 8));
        grid.setVerticalSpacing(dp(activity, 8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);

        final TabAdapter adapter = new TabAdapter(activity, mgr, new TabAdapter.Callbacks() {
            @Override public void onOpen(int index) {
                mgr.switchTo(index);
                dialog.dismiss();
            }
            @Override public void onClose(int index) {
                mgr.closeTab(index);
                if (mgr.getTabCount() == 0) {
                    dialog.dismiss();
                    return;
                }
                notifyChanged(grid, mgr);
            }
        });
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button newTab = new Button(activity);
        newTab.setText("New tab");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(activity, 8);
        newTab.setLayoutParams(btnLp);
        newTab.setOnClickListener(v -> {
            Tab t = mgr.createTab(null);
            mgr.switchTo(mgr.getTabs().indexOf(t));
            dialog.dismiss();
        });
        root.addView(newTab);

        dialog.setContentView(root);
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.background_dark);
        }
    }

    /** Refreshes the grid after a close without rebuilding the dialog. */
    private static void notifyChanged(GridView grid, TabManager mgr) {
        if (grid.getAdapter() instanceof TabAdapter) {
            ((TabAdapter) grid.getAdapter()).notifyDataSetChanged();
        }
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
