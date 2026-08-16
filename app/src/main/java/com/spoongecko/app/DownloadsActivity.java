package com.spoongecko.app;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DownloadsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyView;
    private DownloadAdapter adapter;
    private final List<DownloadItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.downloads_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setFitsSystemWindows(true);
        root.addView(toolbar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        MaterialButton btnClearAll = new MaterialButton(this);
        btnClearAll.setText(R.string.clear_all_downloads);
        btnClearAll.setTextSize(14);
        btnClearAll.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.bottomMargin = dp(8);
        btnClearAll.setLayoutParams(btnParams);
        btnClearAll.setOnClickListener(v -> clearAll());
        content.addView(btnClearAll);

        emptyView = new TextView(this);
        emptyView.setText(R.string.no_downloads);
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(32), 0, 0);
        emptyView.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadAdapter();
        recyclerView.setAdapter(adapter);
        content.addView(recyclerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        loadDownloads();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDownloads();
    }

    private void loadDownloads() {
        items.clear();
        String[] projection = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
        };
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    long size = cursor.isNull(sizeCol) ? 0 : cursor.getLong(sizeCol);
                    long date = cursor.isNull(dateCol) ? 0 : cursor.getLong(dateCol);
                    Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                    items.add(new DownloadItem(uri, name, size, date));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.downloads_load_failed, Toast.LENGTH_SHORT).show();
        }
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void openItem(DownloadItem item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri, getContentResolver().getType(item.uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean deleteItem(DownloadItem item) {
        try {
            return getContentResolver().delete(item.uri, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void clearAll() {
        int count = 0;
        for (DownloadItem item : items) {
            if (deleteItem(item)) count++;
        }
        items.clear();
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, getString(R.string.deleted_files, count), Toast.LENGTH_SHORT).show();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return getString(R.string.size_b, bytes);
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, getString(R.string.size_kb), bytes / 1024.0);
        return String.format(Locale.ROOT, getString(R.string.size_mb), bytes / (1024.0 * 1024.0));
    }

    private String formatDate(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat(getString(R.string.downloads_date_format), Locale.getDefault())
                .format(new Date(millis));
    }

    private class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.Holder> {

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(parent.getContext());
            card.setRadius(dp(12));
            card.setCardElevation(dp(1));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            card.setLayoutParams(params);

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(8), dp(12));
            row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView name = new TextView(parent.getContext());
            name.setTextSize(14);
            name.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(name);

            TextView meta = new TextView(parent.getContext());
            meta.setTextSize(11);
            meta.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
            meta.setPadding(0, dp(4), 0, 0);
            info.addView(meta);

            MaterialButton btnDelete = new MaterialButton(parent.getContext());
            btnDelete.setText("\u2715");
            btnDelete.setTextSize(14);
            btnDelete.setTextColor(getResources().getColor(R.color.md_theme_error, null));
            btnDelete.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnDelete.setPadding(0, 0, 0, 0);
            btnDelete.setMinWidth(0);
            btnDelete.setMinHeight(0);
            btnDelete.setInsetTop(0);
            btnDelete.setInsetBottom(0);

            row.addView(info);
            row.addView(btnDelete);
            card.addView(row);

            return new Holder(card, name, meta, btnDelete);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            DownloadItem item = items.get(position);
            holder.name.setText(item.name);
            holder.meta.setText(formatSize(item.size) + "  |  " + formatDate(item.date));
            holder.card.setOnClickListener(v -> openItem(item));
            holder.btnDelete.setOnClickListener(v -> {
                if (deleteItem(item)) {
                    items.remove(position);
                    notifyItemRemoved(position);
                    boolean empty = items.isEmpty();
                    emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                    Toast.makeText(DownloadsActivity.this, R.string.deleted, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView name;
            final TextView meta;
            final MaterialButton btnDelete;

            Holder(MaterialCardView card, TextView name, TextView meta, MaterialButton btnDelete) {
                super(card);
                this.card = card;
                this.name = name;
                this.meta = meta;
                this.btnDelete = btnDelete;
            }
        }
    }

    private static class DownloadItem {
        final Uri uri;
        final String name;
        final long size;
        final long date;

        DownloadItem(Uri uri, String name, long size, long date) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.date = date;
        }
    }
}
