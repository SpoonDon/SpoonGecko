package com.spoongecko.app;

import android.app.AlertDialog;
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

import java.util.List;

public class BookmarksActivity extends AppCompatActivity {

    private LinearLayout list;
    private EditText searchBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.bookmarks_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 16, 16, 16);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint(R.string.bookmarks_search_hint);
        searchBox.setPadding(0, 12, 0, 12);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.addView(searchBox);

        addSpacer(content, 8);

        MaterialButton btnAdd = new MaterialButton(this);
        btnAdd.setText(R.string.add_bookmark);
        btnAdd.setOnClickListener(v -> showAddDialog());
        content.addView(btnAdd);

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
        List<BookmarkStore.Entry> entries = BookmarkStore.query(this, query);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_bookmarks);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 32, 0, 0);
            list.addView(empty);
            return;
        }

        for (BookmarkStore.Entry entry : entries) {
            list.addView(buildCard(entry));
        }
    }

    private MaterialCardView buildCard(BookmarkStore.Entry entry) {
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

        TextView url = new TextView(this);
        url.setText(entry.url);
        url.setTextSize(11);
        url.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
        info.addView(url);

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
            BookmarkStore.delete(this, entry.id);
            rebuild();
        });

        card.setOnClickListener(v -> RuntimeController.openUrlInMain(this, entry.url));

        row.addView(info);
        row.addView(btnDelete);
        card.addView(row);
        return card;
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 16, 48, 0);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint(R.string.bookmark_url_hint);
        urlInput.setText("https://");
        form.addView(urlInput);

        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint(R.string.bookmark_title_hint);
        form.addView(titleInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.add_bookmark)
                .setView(form)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String url = urlInput.getText().toString().trim();
                    String title = titleInput.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, R.string.bookmark_url_hint, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    boolean ok = BookmarkStore.add(this, url, title);
                    Toast.makeText(this, ok ? R.string.bookmark_added : R.string.bookmark_add_failed,
                            Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addSpacer(LinearLayout parent, int heightDp) {
        TextView spacer = new TextView(this);
        int heightPx = (int) (heightDp * getResources().getDisplayMetrics().density);
        spacer.setHeight(heightPx);
        parent.addView(spacer);
    }
}
