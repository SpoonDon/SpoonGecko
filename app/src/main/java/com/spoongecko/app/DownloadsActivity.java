package com.spoongecko.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DownloadsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Downloads");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setFitsSystemWindows(true);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = downloadsDir.listFiles();

        List<File> fileList = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) fileList.add(f);
            }
            fileList.sort(Comparator.comparingLong(File::lastModified).reversed());
        }

        if (fileList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No downloads yet");
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 32, 0, 0);
            empty.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
            content.addView(empty);
        } else {
            for (File file : fileList) {
                content.addView(buildFileCard(file));
            }
        }

        TextView spacer = new TextView(this);
        spacer.setHeight(16);
        content.addView(spacer);

        MaterialButton btnClearAll = new MaterialButton(this);
        btnClearAll.setText("Clear All Downloads");
        btnClearAll.setTextSize(14);
        btnClearAll.setPadding(0, 14, 0, 14);
        btnClearAll.setOnClickListener(v -> {
            int count = 0;
            for (File f : fileList) {
                if (f.delete()) count++;
            }
            Toast.makeText(this, "Deleted " + count + " files", Toast.LENGTH_SHORT).show();
            finish();
        });
        content.addView(btnClearAll);

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
    }

    private MaterialCardView buildFileCard(File file) {
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
        name.setText(file.getName());
        name.setTextSize(14);
        name.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        info.addView(name);

        TextView meta = new TextView(this);
        meta.setText(formatSize(file.length()) + "  |  " + formatDate(file.lastModified()));
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
            if (file.delete()) {
                ((LinearLayout) card.getParent()).removeView(card);
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            }
        });

        card.setOnClickListener(v -> openFile(file));

        row.addView(info);
        row.addView(btnDelete);
        card.addView(row);
        return card;
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension(file.getName()));
            if (mime == null) mime = "application/octet-stream";

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private String extension(String filename) {
        int i = filename.lastIndexOf('.');
        if (i == -1) return "";
        return filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(new Date(millis));
    }
}
