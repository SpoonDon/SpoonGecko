package com.spoongecko.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class BookmarksActivity extends AppCompatActivity {

    private EditText searchBox;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private BookmarkAdapter adapter;
    private final List<BookmarkStore.Entry> entries = new ArrayList<>();

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

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint(R.string.bookmarks_search_hint);
        searchBox.setPadding(0, dp(12), 0, dp(12));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.addView(searchBox);

        MaterialButton btnAdd = new MaterialButton(this);
        btnAdd.setText(R.string.add_bookmark);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dp(8);
        btnParams.bottomMargin = dp(8);
        btnAdd.setLayoutParams(btnParams);
        btnAdd.setOnClickListener(v -> showAddDialog());
        content.addView(btnAdd);

        emptyView = new TextView(this);
        emptyView.setText(R.string.no_bookmarks);
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(32), 0, 0);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BookmarkAdapter();
        recyclerView.setAdapter(adapter);
        content.addView(recyclerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        rebuild();
    }

    private void rebuild() {
        String query = searchBox.getText().toString().trim();
        entries.clear();
        entries.addAll(BookmarkStore.query(this, query));
        boolean empty = entries.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(16), dp(24), 0);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint(R.string.bookmark_url_hint);
        urlInput.setText("https://");
        form.addView(urlInput);

        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint(R.string.bookmark_title_hint);
        titleInput.setPadding(0, dp(12), 0, 0);
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

    private class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.Holder> {

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

            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView title = new TextView(parent.getContext());
            title.setTextSize(14);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(title);

            TextView url = new TextView(parent.getContext());
            url.setTextSize(11);
            url.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
            info.addView(url);

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

            return new Holder(card, title, url, btnDelete);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            BookmarkStore.Entry entry = entries.get(position);
            holder.title.setText(entry.title != null && !entry.title.isEmpty() ? entry.title : entry.url);
            holder.url.setText(entry.url);
            holder.card.setOnClickListener(v -> RuntimeController.openUrlInMain(BookmarksActivity.this, entry.url));
            holder.btnDelete.setOnClickListener(v -> {
                BookmarkStore.delete(BookmarksActivity.this, entry.id);
                entries.remove(position);
                notifyItemRemoved(position);
                boolean empty = entries.isEmpty();
                emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView title;
            final TextView url;
            final MaterialButton btnDelete;

            Holder(MaterialCardView card, TextView title, TextView url, MaterialButton btnDelete) {
                super(card);
                this.card = card;
                this.title = title;
                this.url = url;
                this.btnDelete = btnDelete;
            }
        }
    }
}
