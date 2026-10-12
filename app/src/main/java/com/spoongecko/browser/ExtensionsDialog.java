package com.spoongecko.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.mozilla.geckoview.WebExtension;

public class ExtensionsDialog {

    public interface OnBrowseAmoListener {
        void onBrowseAmo();
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    public static void show(Context context, OnBrowseAmoListener browseListener) {
        final ExtensionManager manager = ExtensionManager.getInstance();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(context, 16);
        root.setPadding(p, p, p, p);

        final TextView empty = new TextView(context);
        empty.setText("No extensions installed");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(context, 32), 0, dp(context, 32));
        root.addView(empty);

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button browse = new Button(context);
        browse.setText("Browse addons.mozilla.org");
        browse.setOnClickListener(v -> {
            if (browseListener != null) browseListener.onBrowseAmo();
        });
        root.addView(browse);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Extensions")
                .setView(root)
                .setPositiveButton("Close", null)
                .create();

        manager.setListener(extensions -> {
            list.removeAllViews();
            if (extensions.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                scroll.setVisibility(View.GONE);
                return;
            }
            empty.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            for (WebExtension ext : extensions) {
                list.addView(buildRow(context, manager, ext));
            }
        });

        dialog.setOnDismissListener(d -> manager.setListener(null));
        dialog.show();
    }

    private static View buildRow(Context ctx, ExtensionManager manager, WebExtension ext) {
        WebExtension.MetaData meta = ext.metaData;
        String name = (meta != null && meta.name != null) ? meta.name : "Unknown";
        String version = (meta != null && meta.version != null) ? meta.version : "";
        String description = (meta != null && meta.description != null) ? meta.description : "";
        boolean enabled = (meta != null) && meta.enabled;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));

        ImageView icon = new ImageView(ctx);
        int iconSize = dp(ctx, 40);
        row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        if (meta != null && meta.icon != null) {
            meta.icon.getBitmap(iconSize).accept(bmp -> {
                if (bmp != null) icon.setImageBitmap(bmp);
            }, err -> { /* ignore */ });
        }

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMargins(dp(ctx, 12), 0, dp(ctx, 12), 0);
        row.addView(textCol, textLp);

        TextView nameView = new TextView(ctx);
        nameView.setText(name);
        nameView.setTextSize(16);
        nameView.setTypeface(null, Typeface.BOLD);
        textCol.addView(nameView);

        if (!version.isEmpty()) {
            TextView v = new TextView(ctx);
            v.setText(version);
            v.setTextSize(12);
            v.setTextColor(0xFF666666);
            textCol.addView(v);
        }
        if (!description.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(description);
            d.setTextSize(13);
            d.setMaxLines(2);
            textCol.addView(d);
        }

        Switch sw = new Switch(ctx);
        sw.setChecked(enabled);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) manager.enable(ext); else manager.disable(ext);
        });
        row.addView(sw);

        ImageButton del = new ImageButton(ctx);
        del.setImageResource(android.R.drawable.ic_menu_delete);
        del.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setTitle("Uninstall")
                .setMessage("Uninstall \"" + name + "\"?")
                .setPositiveButton("Uninstall", (d, w) -> manager.uninstall(ext))
                .setNegativeButton("Cancel", null)
                .show());
        row.addView(del);

        return row;
    }
}
