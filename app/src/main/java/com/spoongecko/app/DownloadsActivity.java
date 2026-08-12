package com.spoongecko.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class DownloadsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Downloads");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = downloadsDir.listFiles();

        List<File> fileList = new ArrayList<>();
        if (files != null) {
            fileList.addAll(Arrays.asList(files));
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

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextSize(14);
        name.setTextColor(getResources().getColor(R.color.md_theme_on_surface, null));
        info.addView(name);

        TextView size = new TextView(this);
        long bytes = file.length();
        String sizeStr;
        if (bytes < 1024) sizeStr = bytes + " B";
        else if (bytes < 1024 * 1024) sizeStr = String.format("%.1f KB", bytes / 1024.0);
        else sizeStr = String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        size.setText(sizeStr);
        size.setTextSize(11);
        size.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        info.addView(size);

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

        card.setOnClickListener(v -> {
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, getMimeType(file.getName()));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open file", Toast.LENGTH_SHORT).show();
            }
        });

        row.addView(info);
        row.addView(btnDelete);
        card.addView(row);
        return card;
    }

    private String getMimeType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "pdf": return "application/pdf";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "apk": return "application/vnd.android.package-archive";
            case "zip": return "application/zip";
            default: return "*/*";
        }
    }
}
