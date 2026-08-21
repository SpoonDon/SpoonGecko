package com.spoongecko.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VaultActivity extends AppCompatActivity {

    private static final int REQ_IMPORT = 1001;
    private static final int REQ_EXPORT = 1002;

    private final VaultAdapter adapter = new VaultAdapter();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ClipboardManager clipboard;
    private String lastCopied;

    private final Runnable clearClipboard = () -> {
        ClipData data = clipboard.getPrimaryClip();
        if (data != null && data.getItemCount() > 0
                && lastCopied != null
                && lastCopied.equals(String.valueOf(data.getItemAt(0).getText()))) {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        }
        lastCopied = null;
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_vault);

        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        RecyclerView list = findViewById(R.id.vault_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        adapter.setListener(new VaultAdapter.Listener() {
            @Override
            public void onCopyUsername(VaultEntry entry) {
                copy(entry.username);
            }

            @Override
            public void onCopyPassword(VaultEntry entry) {
                copy(entry.password);
            }

            @Override
            public void onDelete(VaultEntry entry) {
                confirmDelete(entry);
            }
        });

        findViewById(R.id.vault_add).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.vault_import).setOnClickListener(v -> pickCsv(REQ_IMPORT));
        findViewById(R.id.vault_export).setOnClickListener(v -> exportCsv());

        EditText search = findViewById(R.id.vault_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        reload();
    }

    private void reload() {
        SecureCredentialManager.get(this).getAllCredentials(entries -> {
            List<VaultEntry> converted = new ArrayList<>();
            for (SecureCredentialManager.Entry entry : entries) {
                converted.add(new VaultEntry(entry.host, entry.username, entry.password));
            }
            main.post(() -> adapter.submitAll(converted));
        });
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("spoonvault", text));
        lastCopied = text;
        Toast.makeText(this, R.string.vault_copied, Toast.LENGTH_SHORT).show();
        main.removeCallbacks(clearClipboard);
        main.postDelayed(clearClipboard, 60000L);
    }

    private void confirmDelete(VaultEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.vault_delete)
                .setMessage(getString(R.string.vault_delete_confirm, entry.host, entry.username))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    SecureCredentialManager.get(this).deleteCredentials(entry.host, entry.username);
                    main.postDelayed(this::reload, 300L);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddDialog() {
        EditText host = new EditText(this);
        host.setHint(R.string.vault_add_dialog_host);
        EditText user = new EditText(this);
        user.setHint(R.string.vault_add_dialog_username);
        EditText pass = new EditText(this);
        pass.setHint(R.string.vault_add_dialog_password);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        box.addView(host);
        box.addView(user);
        box.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle(R.string.vault_add_dialog_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String h = host.getText().toString().trim();
                    String u = user.getText().toString().trim();
                    String p = pass.getText().toString();
                    if (h.isEmpty() || u.isEmpty() || p.isEmpty()) return;
                    SecureCredentialManager.get(this).saveCredentials(h, u, p);
                    main.postDelayed(this::reload, 300L);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickCsv(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, requestCode);
    }

    private void importCsv(Uri uri) {
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) return;
            SecureCredentialManager.get(this).importFromCsv(input, () ->
                    main.post(() -> {
                        Toast.makeText(this, R.string.vault_imported, Toast.LENGTH_SHORT).show();
                        reload();
                    }));
        } catch (Exception ignored) {
        }
    }

    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "spoonvault.csv");
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void writeExport(Uri uri) {
        SecureCredentialManager.get(this).getExportCsv(csv ->
                io.execute(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) out.write(csv.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                    }
                    main.post(() ->
                            Toast.makeText(this, R.string.vault_exported, Toast.LENGTH_SHORT).show());
                }));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_IMPORT) {
            importCsv(data.getData());
        } else if (requestCode == REQ_EXPORT) {
            writeExport(data.getData());
        }
    }
}
