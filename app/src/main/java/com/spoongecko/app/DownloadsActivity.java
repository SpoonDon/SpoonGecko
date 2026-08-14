package com.spoongecko.app;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DownloadsActivity extends AppCompatActivity {

    private LinearLayout content;
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

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);
        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);

        TextView spacer = new TextView(this);
        spacer.setHeight(16);
        content.addView(spacer);

        MaterialButton btnClearAll = new MaterialButton(this);
        btnClearAll.setText(R.string.clear_all_downloads);
        btnClearAll.setTextSize(14);
        btnClearAll.setPadding(0, 14, 0, 14);
        btnClearAll.setOnClickListener(v -> clearAll());
        content.addView(btnClearAll);

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
        rebuildList();
    }

    private void rebuildList() {
        int childCount = content.getChildCount();
        if (childCount > 2) {
            content.removeViews(2, childCount - 2);
        }

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_downloads);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 32, 0, 0);
            empty.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
            content.addView(empty, 2);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            content.addView(buildFileCard(items.get(i)), 2 + i);
        }
    }

    private MaterialCardView buildFileCard(DownloadItem item) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(12);
        card.setCardElevation(1);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 8);
        card.setLayoutParams(params);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 12, 8, 12);
        row.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextSize(14);
        name.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        info.addView(name);

        TextView meta = new TextView(this);
        meta.setText(formatSize(item.size) + "  |  " + formatDate(item.date));
        meta.setTextSize(11);
        meta.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        meta.setPadding(0, 4, 0, 0);
        info.addView(meta);

        MaterialButton btnDelete = new MaterialButton(this);
        btnDelete.setText("\u2715");
        btnDelete.setTextSize(14);
        btnDelete.setTextColor(getResources().getColor(R.color.md_theme_error, null));
        btnDelete.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnDelete.setPadding(0, 0, 0, 0);
        btnDelete.setMinWidth(0);
        btnDelete.setMinHeight(0);
        btnDelete.setInsetTop(0);
        btnDelete.setInsetBottom(0);
        btnDelete.setOnClickListener(v -> {
            if (deleteItem(item)) {
                items.remove(item);
                rebuildList();
                Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
            }
        });

        card.setOnClickListener(v -> openItem(item));

        row.addView(info);
        row.addView(btnDelete);
        card.addView(row);
        return card;
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
        rebuildList();
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
