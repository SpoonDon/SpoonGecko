package com.spoongecko.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple modal listing of active + completed downloads.
 *
 * Tap a completed row -> open.
 * Long-press any row    -> Share / Delete menu.
 */
public final class DownloadsDialog {

    private DownloadsDialog() {}

    public static void show(Context ctx) {
        final DownloadManager mgr = DownloadManager.getInstance(ctx);

        final ListView list = new ListView(ctx);
        list.setDivider(null);
        list.setDividerHeight(0);

        final Adapter adapter = new Adapter(ctx, mgr.getDownloads());
        list.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Downloads")
                .setView(list)
                .setNegativeButton("Close", null)
                .setNeutralButton("Clear finished", (d, w) -> {
                    mgr.clearCompleted();
                    adapter.replace(mgr.getDownloads());
                })
                .create();

        list.setOnItemClickListener((p, v, pos, id) -> {
            Download d = adapter.getItem(pos);
            if (d != null && d.state == Download.State.COMPLETE) {
                mgr.open(d);
            }
        });

        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Download d = adapter.getItem(pos);
            if (d == null) return true;
            final CharSequence[] items = d.state == Download.State.COMPLETE
                    ? new CharSequence[]{ "Share", "Delete", "Cancel" }
                    : new CharSequence[]{ "Cancel download", "Cancel" };
            new AlertDialog.Builder(ctx)
                    .setItems(items, (dd, which) -> {
                        if (d.state == Download.State.COMPLETE && which == 0) {
                            mgr.share(d);
                        } else if (which == 0 || which == 1) {
                            if (d.isActive()) mgr.cancel(d);
                            else { mgr.delete(d); adapter.replace(mgr.getDownloads()); }
                        }
                    })
                    .show();
            return true;
        });

        final DownloadManager.Listener listener = new DownloadManager.Listener() {
            @Override public void onDownloadsChanged() {
                adapter.replace(mgr.getDownloads());
            }
            @Override public void onDownloadComplete(Download d) {}
            @Override public void onDownloadFailed(Download d, String error) {}
        };
        mgr.setListener(listener);

        dialog.setOnDismissListener(d -> {
            if (mgr.hasListener(listener)) mgr.setListener(null);
        });
        dialog.show();
    }

    // -------------------------------------------------------------- adapter

    private static final class Adapter extends BaseAdapter {
        private final Context ctx;
        private final List<Download> data = new ArrayList<>();

        Adapter(Context ctx, List<Download> initial) {
            this.ctx = ctx;
            replace(initial);
        }

        void replace(List<Download> newData) {
            data.clear();
            if (newData != null) data.addAll(newData);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Download getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return data.get(i).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView instanceof Row) {
                row = (Row) convertView;
            } else {
                row = new Row(ctx);
            }
            row.bind(getItem(position));
            return row;
        }
    }

    private static final class Row extends LinearLayout {
        private final TextView name;
        private final TextView status;
        private final ProgressBar bar;

        Row(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            int padH = dp(ctx, 16), padV = dp(ctx, 12);
            setPadding(padH, padV, padH, padV);

            name = new TextView(ctx);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            addView(name, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            status = new TextView(ctx);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            status.setTextColor(0xFF666666);
            status.setPadding(0, dp(ctx, 2), 0, 0);
            addView(status, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            bar = new ProgressBar(ctx, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            LayoutParams lp = new LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(ctx, 4));
            lp.topMargin = dp(ctx, 6);
            addView(bar, lp);
        }

        void bind(Download d) {
            name.setText(d.displayName());

            String sizePart = formatSize(d.bytesRead);
            if (d.totalBytes > 0) sizePart += " / " + formatSize(d.totalBytes);

            switch (d.state) {
                case QUEUED:
                case RUNNING:
                    status.setText("Downloading — " + sizePart);
                    bar.setVisibility(VISIBLE);
                    int pct = d.progressPercent();
                    if (pct < 0) {
                        bar.setIndeterminate(true);
                    } else {
                        bar.setIndeterminate(false);
                        bar.setProgress(pct);
                    }
                    break;
                case COMPLETE:
                    status.setText("Complete — " + sizePart);
                    bar.setVisibility(GONE);
                    break;
                case FAILED:
                    status.setText("Failed — "
                            + (d.error == null ? "unknown" : d.error));
                    status.setTextColor(0xFFCC3333);
                    bar.setVisibility(GONE);
                    break;
                case CANCELED:
                    status.setText("Canceled");
                    bar.setVisibility(GONE);
                    break;
            }
            if (d.state != Download.State.FAILED) {
                status.setTextColor(0xFF666666);
            }
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024)
                return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024)
                return String.format(java.util.Locale.US, "%.1f MB",
                        bytes / (1024.0 * 1024));
            return String.format(java.util.Locale.US, "%.2f GB",
                    bytes / (1024.0 * 1024 * 1024));
        }

        private static int dp(Context ctx, int dp) {
            return Math.round(dp * ctx.getResources()
                    .getDisplayMetrics().density);
        }
    }
}
