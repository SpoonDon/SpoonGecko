package com.spoongecko.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog that lists installed extensions and lets the user enable, disable,
 * uninstall, or browse AMO for new ones.
 */
public class ExtensionsDialog {

    public interface OnBrowseAmoListener {
        void onBrowseAmo();
    }

    public static void show(Context context, OnBrowseAmoListener browseListener) {
        final ExtensionManager manager = ExtensionManager.getInstance();

        View root = LayoutInflater.from(context).inflate(R.layout.dialog_extensions, null);
        RecyclerView list = root.findViewById(R.id.extensionsList);
        TextView empty = root.findViewById(R.id.emptyText);

        ExtensionAdapter adapter = new ExtensionAdapter(context, manager);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Extensions")
                .setView(root)
                .setPositiveButton("Close", null)
                .create();

        root.findViewById(R.id.browseAmoButton).setOnClickListener(v -> {
            dialog.dismiss();
            if (browseListener != null) {
                browseListener.onBrowseAmo();
            }
        });

        manager.setListener(newList -> {
            adapter.submit(newList);
            boolean isEmpty = newList.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        });

        dialog.setOnDismissListener(d -> manager.setListener(null));
        dialog.show();
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private static class ExtensionAdapter extends RecyclerView.Adapter<ExtensionAdapter.VH> {

        private final Context mContext;
        private final ExtensionManager mManager;
        private final List<WebExtension> mItems = new ArrayList<>();

        ExtensionAdapter(Context context, ExtensionManager manager) {
            mContext = context;
            mManager = manager;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_extension, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            WebExtension ext = mItems.get(position);
            WebExtension.MetaData meta = ext.metaData;

            holder.name.setText(meta.name != null ? meta.name : "Unknown");
            holder.version.setText(meta.version != null ? meta.version : "");
            holder.description.setText(meta.description != null ? meta.description : "");

            // Icon — Image.getBitmap returns GeckoResult<Bitmap>, NOT Bitmap.
            holder.icon.setImageBitmap(null);
            if (meta.icon != null) {
                meta.icon.getBitmap(80).accept(bitmap -> {
                    if (bitmap != null) {
                        holder.icon.setImageBitmap(bitmap);
                    }
                }, throwable -> {
                    // Icon loading failed — leave placeholder.
                });
            }

            // Switch — clear listener first to avoid feedback loops.
            holder.enabledSwitch.setOnCheckedChangeListener(null);
            holder.enabledSwitch.setChecked(meta.enabled);
            holder.enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    mManager.enable(ext);
                } else {
                    mManager.disable(ext);
                }
            });

            holder.delete.setOnClickListener(v -> {
                String displayName = meta.name != null ? meta.name : "this extension";
                new AlertDialog.Builder(mContext)
                        .setTitle("Uninstall")
                        .setMessage("Uninstall \"" + displayName + "\"?")
                        .setPositiveButton("Uninstall", (d, w) -> mManager.uninstall(ext))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        void submit(List<WebExtension> list) {
            mItems.clear();
            mItems.addAll(list);
            notifyDataSetChanged();
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView version;
            final TextView description;
            final Switch enabledSwitch;
            final ImageButton delete;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.extensionIcon);
                name = itemView.findViewById(R.id.extensionName);
                version = itemView.findViewById(R.id.extensionVersion);
                description = itemView.findViewById(R.id.extensionDescription);
                enabledSwitch = itemView.findViewById(R.id.extensionSwitch);
                delete = itemView.findViewById(R.id.deleteButton);
            }
        }
    }
}
