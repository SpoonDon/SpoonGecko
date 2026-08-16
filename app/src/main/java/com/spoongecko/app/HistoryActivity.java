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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    private EditText searchBox;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private HistoryAdapter adapter;
    private final List<HistoryStore.Entry> entries = new ArrayList<>();

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

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint(R.string.history_search_hint);
        searchBox.setPadding(0, dp(12), 0, dp(12));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.addView(searchBox);

        MaterialButton btnClear = new MaterialButton(this);
        btnClear.setText(R.string.clear_all_history);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dp(8);
        btnParams.bottomMargin = dp(8);
        btnClear.setLayoutParams(btnParams);
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

        emptyView = new TextView(this);
        emptyView.setText(R.string.no_history);
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(32), 0, 0);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
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
        entries.addAll(HistoryStore.query(this, query, 200));
        boolean empty = entries.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat(getString(R.string.downloads_date_format), Locale.getDefault())
                .format(new Date(millis));
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

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

            TextView meta = new TextView(parent.getContext());
            meta.setTextSize(11);
            meta.setTextColor(getResources().getColor(R.color.md_theme_on_surface_variant, null));
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

            return new Holder(card, title, meta, btnDelete);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            HistoryStore.Entry entry = entries.get(position);
            holder.title.setText(entry.title != null && !entry.title.isEmpty() ? entry.title : entry.url);
            holder.meta.setText(entry.url + "\n" + formatDate(entry.visitedAt) + "  |  " + entry.visitCount + " visits");
            holder.card.setOnClickListener(v -> RuntimeController.openUrlInMain(HistoryActivity.this, entry.url));
            holder.btnDelete.setOnClickListener(v -> {
                HistoryStore.delete(HistoryActivity.this, entry.id);
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
            final TextView meta;
            final MaterialButton btnDelete;

            Holder(MaterialCardView card, TextView title, TextView meta, MaterialButton btnDelete) {
                super(card);
                this.card = card;
                this.title = title;
                this.meta = meta;
                this.btnDelete = btnDelete;
            }
        }
    }
}
