package com.spoongecko.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    private LinearLayout list;
    private EditText searchBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.history_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint(R.string.history_search_hint);
        searchBox.setPadding(0, 12, 0, 12);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.addView(searchBox);

        addSpacer(content, 8);

        MaterialButton btnClear = new MaterialButton(this);
        btnClear.setText(R.string.clear_all_history);
        btnClear.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.clear_all_history)
                    .setMessage(R.string.clear_history_confirm)
                    .setPositiveButton(R.string.clear, (d, w) -> {
                        HistoryStore.clear(this);
                        rebuild();
                        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        content.addView(btnClear);

        addSpacer(content, 8);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);

        rebuild();
    }

    private void rebuild() {
        list.removeAllViews();
        String query = searchBox.getText().toString().trim();
        List<HistoryStore.Entry> entries = HistoryStore.query(this, query, 200);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_history);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 32, 0, 0);
            list.addView(empty);
            return;
        }

        for (HistoryStore.Entry entry : entries) {
            list.addView(buildCard(entry));
        }
    }

    private MaterialCardView buildCard(HistoryStore.Entry entry) {
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
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setText(entry.title != null && !entry.title.isEmpty() ? entry.title : entry.url);
        title.setTextSize(14);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);

        TextView meta = new TextView(this);
        meta.setText(entry.url + "\n" + formatDate(entry.visitedAt) + "  |  " + entry.visitCount + " visits");
        meta.setTextSize(11);
        meta.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
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
            HistoryStore.delete(this, entry.id);
            rebuild();
        });

        card.setOnClickListener(v -> RuntimeController.openUrlInMain(this, entry.url));

        row.addView(info);
        row.addView(btnDelete);
        card.addView(row);
        return card;
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat(getString(R.string.downloads_date_format), Locale.getDefault())
                .format(new Date(millis));
    }

    private void addSpacer(LinearLayout parent, int heightDp) {
        TextView spacer = new TextView(this);
        int heightPx = (int) (heightDp * getResources().getDisplayMetrics().density);
        spacer.setHeight(heightPx);
        parent.addView(spacer);
    }
}
