package com.spoongecko.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class VaultAdapter extends ListAdapter<VaultEntry, VaultAdapter.Holder> {

    interface Listener {
        void onCopyUsername(VaultEntry entry);
        void onCopyPassword(VaultEntry entry);
        void onDelete(VaultEntry entry);
    }

    private static final DiffUtil.ItemCallback<VaultEntry> DIFF =
            new DiffUtil.ItemCallback<VaultEntry>() {
                @Override
                public boolean areItemsTheSame(@NonNull VaultEntry a, @NonNull VaultEntry b) {
                    return a.host.equals(b.host) && a.username.equals(b.username);
                }

                @Override
                public boolean areContentsTheSame(@NonNull VaultEntry a, @NonNull VaultEntry b) {
                    return a.host.equals(b.host)
                            && a.username.equals(b.username)
                            && a.password.equals(b.password);
                }
            };

    private final List<VaultEntry> all = new ArrayList<>();
    private Listener listener;
    private String query = "";

    VaultAdapter() {
        super(DIFF);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void submitAll(List<VaultEntry> entries) {
        all.clear();
        all.addAll(entries);
        applyFilter();
    }

    void filter(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        List<VaultEntry> filtered = new ArrayList<>();
        for (VaultEntry entry : all) {
            if (query.isEmpty()
                    || entry.host.toLowerCase(Locale.ROOT).contains(query)
                    || entry.username.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(entry);
            }
        }
        submitList(filtered);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView host;
        final TextView user;
        final TextView copyUser;
        final TextView copyPass;
        final TextView delete;

        Holder(View view) {
            super(view);
            host = view.findViewById(R.id.vault_host);
            user = view.findViewById(R.id.vault_user);
            copyUser = view.findViewById(R.id.vault_copy_user);
            copyPass = view.findViewById(R.id.vault_copy_pass);
            delete = view.findViewById(R.id.vault_delete);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.vault_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        VaultEntry entry = getItem(position);
        holder.host.setText(entry.host);
        holder.user.setText(entry.username);
        holder.copyUser.setOnClickListener(v -> {
            if (listener != null) listener.onCopyUsername(entry);
        });
        holder.copyPass.setOnClickListener(v -> {
            if (listener != null) listener.onCopyPassword(entry);
        });
        holder.delete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(entry);
        });
    }
}
