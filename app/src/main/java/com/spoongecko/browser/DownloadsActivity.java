package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-app Downloads Manager: live queue progress, pause/resume/retry/cancel,
 * manual URL downloads, file open/share/delete, and Downloads folder shortcut.
 */
public class DownloadsActivity extends Activity implements DownloadEngine.Listener {

    private enum Filter { ALL, ACTIVE, COMPLETED }

    private static final int REQ_POST_NOTIFICATIONS = 4201;

    private DownloadEngine engine;
    private Adapter adapter;
    private ListView listView;
    private TextView emptyView;
    private TextView summaryView;
    private TextView filterAll;
    private TextView filterActive;
    private TextView filterCompleted;
    private TextView pauseAllBtn;
    private TextView resumeAllBtn;
    private TextView clearFinishedBtn;

    private Filter currentFilter = Filter.ALL;
    private final List<Dialog> activeDialogs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        engine = DownloadEngine.get(this);

        findViewById(R.id.btn_dl_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_dl_folder).setOnClickListener(v -> DownloadEngine.openDownloadsFolder(this));
        findViewById(R.id.btn_dl_add).setOnClickListener(v -> showAddUrlDialog());

        summaryView = findViewById(R.id.dl_summary);
        filterAll = findViewById(R.id.dl_filter_all);
        filterActive = findViewById(R.id.dl_filter_active);
        filterCompleted = findViewById(R.id.dl_filter_completed);
        pauseAllBtn = findViewById(R.id.btn_dl_pause_all);
        resumeAllBtn = findViewById(R.id.btn_dl_resume_all);
        clearFinishedBtn = findViewById(R.id.btn_dl_clear_finished);

        filterAll.setOnClickListener(v -> setFilter(Filter.ALL));
        filterActive.setOnClickListener(v -> setFilter(Filter.ACTIVE));
        filterCompleted.setOnClickListener(v -> setFilter(Filter.COMPLETED));

        pauseAllBtn.setOnClickListener(v -> engine.pauseAll());
        resumeAllBtn.setOnClickListener(v -> {
            ensureNotificationPermission();
            engine.resumeAll(ExtensionManager.ensureRuntime(this));
        });
        clearFinishedBtn.setOnClickListener(v -> engine.clearFinished());

        adapter = new Adapter();
        listView = findViewById(R.id.dl_list);
        emptyView = findViewById(R.id.dl_empty);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            final DownloadEntry item = adapter.getItem(position);
            if (item == null) return;
            if (item.status == DownloadEntry.Status.COMPLETED && engine.isBackedFilePresent(item)) {
                engine.openDownloadedFile(this, item);
            } else {
                showDetailsDialog(item);
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            final DownloadEntry item = adapter.getItem(position);
            if (item != null) showDetailsDialog(item);
            return true;
        });

        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(this);
        refreshUi();
    }

    @Override
    protected void onStop() {
        engine.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        for (Dialog d : new ArrayList<>(activeDialogs)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {}
        }
        activeDialogs.clear();
        super.onDestroy();
    }

    @Override
    public void onDownloadsChanged() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                refreshUi();
            }
        });
    }

    private void setFilter(Filter f) {
        if (currentFilter == f) return;
        currentFilter = f;
        refreshUi();
    }

    private void refreshUi() {
        final List<DownloadEntry> all = engine.getEntries();
        final List<DownloadEntry> filtered = new ArrayList<>();
        int activeCount = 0;
        int pausedResumableCount = 0;
        int activePausableCount = 0;
        int finishedCount = 0;

        for (DownloadEntry e : all) {
            if (e.status.isActive()) activeCount++;
            if (e.canPause()) activePausableCount++;
            if (e.canResume()) pausedResumableCount++;
            if (!e.status.isInProgressOrPaused()) finishedCount++;

            if (currentFilter == Filter.ALL) {
                filtered.add(e);
            } else if (currentFilter == Filter.ACTIVE) {
                if (e.status.isInProgressOrPaused() || e.status == DownloadEntry.Status.FAILED) {
                    filtered.add(e);
                }
            } else {
                if (e.status == DownloadEntry.Status.COMPLETED) {
                    filtered.add(e);
                }
            }
        }

        if (all.isEmpty()) {
            summaryView.setText("No downloads");
        } else if (activeCount > 0) {
            summaryView.setText(activeCount + " active \u00b7 " + all.size() + " total");
        } else {
            summaryView.setText(all.size() + (all.size() == 1 ? " file" : " files"));
        }

        styleFilterChip(filterAll, currentFilter == Filter.ALL);
        styleFilterChip(filterActive, currentFilter == Filter.ACTIVE);
        styleFilterChip(filterCompleted, currentFilter == Filter.COMPLETED);

        setActionEnabled(pauseAllBtn, activePausableCount > 0);
        setActionEnabled(resumeAllBtn, pausedResumableCount > 0);
        setActionEnabled(clearFinishedBtn, finishedCount > 0);

        if (filtered.isEmpty()) {
            if (currentFilter == Filter.ACTIVE) {
                emptyView.setText("No active or paused downloads.");
            } else if (currentFilter == Filter.COMPLETED) {
                emptyView.setText("No completed downloads yet.");
            } else {
                emptyView.setText("No downloads yet.\nFiles you save from web pages or add with + appear here.");
            }
        }

        adapter.setData(filtered);
    }

    private static void styleFilterChip(TextView v, boolean selected) {
        v.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        v.setTextColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
    }

    private static void setActionEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.38f);
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            final String perm = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, REQ_POST_NOTIFICATIONS);
            }
        }
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showAddUrlDialog() {
        final LinearLayout content = dialogContent(20);
        content.addView(text("New download", 0xFFFFFFFF, 17, true));

        final TextView subtitle = text("Enter a direct http:// or https:// file link.",
                0x99FFFFFF, 12, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        content.addView(subtitle);

        final EditText input = new EditText(this);
        input.setHint("https://example.com/archive.zip");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x66FFFFFF);
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackgroundResource(R.drawable.bg_glass_input);
        final LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        ilp.topMargin = dp(12);
        content.addView(input, ilp);

        final TextView startBtn = button("Download", false);
        final TextView cancelBtn = button("Cancel", false);
        content.addView(startBtn);
        content.addView(cancelBtn);

        final AlertDialog dialog = glassDialog(content);
        startBtn.setOnClickListener(v -> {
            String raw = input.getText().toString().trim();
            if (raw.isEmpty()) {
                Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!raw.contains("://")) raw = "https://" + raw;
            final String lower = raw.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
                Toast.makeText(this, "Only http:// and https:// links are supported",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            ensureNotificationPermission();
            engine.enqueue(ExtensionManager.ensureRuntime(this), DownloadRequest.forUrl(raw, null));
        });
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
    }

    private void showDetailsDialog(final DownloadEntry e) {
        final LinearLayout content = dialogContent(20);
        content.addView(text(e.fileName, 0xFFFFFFFF, 17, true));

        final TextView statusLine = text(statusText(e), statusColor(e), 12, true);
        statusLine.setPadding(0, dp(4), 0, dp(8));
        content.addView(statusLine);

        addDetailRow(content, "Progress", progressDetails(e));
        addDetailRow(content, "Source", e.hostLabel());
        if (!e.url.isEmpty()) addDetailRow(content, "Address", e.url);
        addDetailRow(content, "Type", e.mimeType);
        addDetailRow(content, "Updated",
                DateFormat.getMediumDateFormat(this).format(new Date(e.updatedAt))
                        + " " + DateFormat.getTimeFormat(this).format(new Date(e.updatedAt)));
        if (e.localPath != null && !e.localPath.isEmpty()) {
            addDetailRow(content, "Saved path", e.localPath);
        }
        if (e.errorMessage != null && !e.errorMessage.isEmpty()) {
            addDetailRow(content, "Error", e.errorMessage);
        }

        final AlertDialog[] dialogRef = new AlertDialog[1];

        if (e.status == DownloadEntry.Status.COMPLETED) {
            final TextView openBtn = button("Open file", false);
            openBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                engine.openDownloadedFile(this, e);
            });
            content.addView(openBtn);

            final TextView shareBtn = button("Share file", false);
            shareBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                engine.shareDownloadedFile(this, e);
            });
            content.addView(shareBtn);
        }
        if (e.canPause()) {
            final TextView pauseBtn = button("Pause", false);
            pauseBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                engine.pause(e.id);
            });
            content.addView(pauseBtn);
        } else if (e.canResume()) {
            final TextView resumeBtn = button("Resume", false);
            resumeBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                ensureNotificationPermission();
                engine.resumeOrRetry(ExtensionManager.ensureRuntime(this), e.id);
            });
            content.addView(resumeBtn);
        } else if (e.canRetry()) {
            final TextView retryBtn = button("Retry download", false);
            retryBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                ensureNotificationPermission();
                engine.resumeOrRetry(ExtensionManager.ensureRuntime(this), e.id);
            });
            content.addView(retryBtn);
        }
        if (e.canCancel()) {
            final TextView cancelBtn = button("Cancel transfer", true);
            cancelBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                engine.cancel(e.id);
            });
            content.addView(cancelBtn);
        }
        if (!e.url.isEmpty()) {
            final TextView copyBtn = button("Copy download URL", false);
            copyBtn.setOnClickListener(v -> {
                PopupsController.copyToClipboard(this, e.url);
                dialogRef[0].dismiss();
            });
            content.addView(copyBtn);
        }

        final TextView removeHistoryBtn = button("Remove from list", false);
        removeHistoryBtn.setOnClickListener(v -> {
            dialogRef[0].dismiss();
            engine.removeEntry(e.id, false);
        });
        content.addView(removeHistoryBtn);

        if (e.status == DownloadEntry.Status.COMPLETED && engine.isBackedFilePresent(e)) {
            final TextView deleteFileBtn = button("Delete file from device", true);
            deleteFileBtn.setOnClickListener(v -> {
                dialogRef[0].dismiss();
                trackDialog(new AlertDialog.Builder(this)
                        .setTitle("Delete file?")
                        .setMessage("Delete \"" + e.fileName + "\" from device storage?")
                        .setPositiveButton("Delete", (d2, w) -> engine.removeEntry(e.id, true))
                        .setNegativeButton("Cancel", null)
                        .show());
            });
            content.addView(deleteFileBtn);
        }

        final TextView closeBtn = button("Close", false);
        closeBtn.setOnClickListener(v -> dialogRef[0].dismiss());
        content.addView(closeBtn);

        dialogRef[0] = glassDialog(content);
    }

    private void addDetailRow(LinearLayout parent, String label, String value) {
        final TextView lbl = text(label, 0x66FFFFFF, 11, true);
        lbl.setPadding(0, dp(6), 0, 0);
        parent.addView(lbl);
        final TextView val = text(value, 0xCCFFFFFF, 12, false);
        val.setMaxLines(3);
        val.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(val);
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private static String statusText(DownloadEntry e) {
        switch (e.status) {
            case QUEUED: return "Queued";
            case RUNNING: return e.totalBytes > 0 ? e.progressPercent() + "%" : "Downloading";
            case PAUSED: return "Paused";
            case SAVING: return "Saving\u2026";
            case COMPLETED: return "Completed";
            case FAILED: return "Failed";
            case CANCELLED: return "Cancelled";
            default: return "";
        }
    }

    private static int statusColor(DownloadEntry e) {
        switch (e.status) {
            case RUNNING:
            case SAVING: return 0xFF64D2FF;
            case PAUSED: return 0xFFFFD666;
            case COMPLETED: return 0xFF66DD88;
            case FAILED:
            case CANCELLED: return 0xFFFF8888;
            default: return 0xB3FFFFFF;
        }
    }

    private String progressDetails(DownloadEntry e) {
        final String done = Formatter.formatShortFileSize(this, e.bytesDone);
        final String total = e.totalBytes > 0
                ? Formatter.formatShortFileSize(this, e.totalBytes) : null;
        if (e.status == DownloadEntry.Status.RUNNING) {
            final String speed = e.speedBytesPerSec > 0
                    ? " \u00b7 " + Formatter.formatShortFileSize(this, e.speedBytesPerSec) + "/s"
                    : "";
            return (total != null ? done + " / " + total : done) + speed;
        }
        if (e.status == DownloadEntry.Status.PAUSED) {
            return (total != null ? done + " / " + total : done) + " \u00b7 Paused";
        }
        if (e.status == DownloadEntry.Status.FAILED) {
            final String err = (e.errorMessage != null && !e.errorMessage.isEmpty())
                    ? e.errorMessage : "Transfer failed";
            return e.hostLabel() + " \u00b7 " + err;
        }
        if (e.status == DownloadEntry.Status.COMPLETED) {
            return e.hostLabel() + " \u00b7 " + (total != null ? total : done);
        }
        return e.hostLabel();
    }

    // ------------------------------------------------------------------
    // List adapter
    // ------------------------------------------------------------------

    private static final class ViewHolder {
        final TextView badge;
        final TextView name;
        final TextView status;
        final TextView meta;
        final ProgressBar progress;
        final ImageButton primary;
        final ImageButton secondary;

        ViewHolder(View v) {
            this.badge = v.findViewById(R.id.dl_badge);
            this.name = v.findViewById(R.id.dl_name);
            this.status = v.findViewById(R.id.dl_status);
            this.meta = v.findViewById(R.id.dl_meta);
            this.progress = v.findViewById(R.id.dl_progress);
            this.primary = v.findViewById(R.id.dl_btn_primary);
            this.secondary = v.findViewById(R.id.dl_btn_secondary);
        }
    }

    private final class Adapter extends BaseAdapter {
        private List<DownloadEntry> items = new ArrayList<>();

        void setData(List<DownloadEntry> data) {
            this.items = data != null ? data : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public DownloadEntry getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return items.get(pos).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v;
            final ViewHolder h;
            if (convertView == null) {
                v = getLayoutInflater().inflate(R.layout.item_download, parent, false);
                h = new ViewHolder(v);
                v.setTag(h);
            } else {
                v = convertView;
                h = (ViewHolder) v.getTag();
            }

            final DownloadEntry e = getItem(position);
            h.badge.setText(e.extensionBadge());
            h.name.setText(e.fileName);
            h.status.setText(statusText(e));
            h.status.setTextColor(statusColor(e));
            h.meta.setText(progressDetails(e));

            final boolean showProgress = e.status == DownloadEntry.Status.RUNNING
                    || e.status == DownloadEntry.Status.PAUSED
                    || e.status == DownloadEntry.Status.QUEUED
                    || e.status == DownloadEntry.Status.SAVING;
            if (showProgress) {
                h.progress.setVisibility(View.VISIBLE);
                final boolean indet = e.status == DownloadEntry.Status.QUEUED
                        || e.status == DownloadEntry.Status.SAVING
                        || e.totalBytes <= 0;
                h.progress.setIndeterminate(indet);
                if (!indet) h.progress.setProgress(e.progressPercent());
            } else {
                h.progress.setVisibility(View.GONE);
            }

            // Primary action button
            if (e.canPause()) {
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_pause);
                h.primary.setContentDescription("Pause " + e.fileName);
                h.primary.setOnClickListener(btn -> engine.pause(e.id));
            } else if (e.canResume()) {
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_play);
                h.primary.setContentDescription("Resume " + e.fileName);
                h.primary.setOnClickListener(btn -> {
                    ensureNotificationPermission();
                    engine.resumeOrRetry(ExtensionManager.ensureRuntime(DownloadsActivity.this), e.id);
                });
            } else if (e.canRetry()) {
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_retry);
                h.primary.setContentDescription("Retry " + e.fileName);
                h.primary.setOnClickListener(btn -> {
                    ensureNotificationPermission();
                    engine.resumeOrRetry(ExtensionManager.ensureRuntime(DownloadsActivity.this), e.id);
                });
            } else if (e.status == DownloadEntry.Status.COMPLETED) {
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_share);
                h.primary.setContentDescription("Share " + e.fileName);
                h.primary.setOnClickListener(btn -> engine.shareDownloadedFile(DownloadsActivity.this, e));
            } else {
                h.primary.setVisibility(View.GONE);
                h.primary.setOnClickListener(null);
            }

            // Secondary action button
            if (e.canCancel()) {
                h.secondary.setImageResource(R.drawable.ic_close);
                h.secondary.setContentDescription("Cancel " + e.fileName);
                h.secondary.setOnClickListener(btn -> engine.cancel(e.id));
            } else {
                h.secondary.setImageResource(R.drawable.ic_delete);
                h.secondary.setContentDescription("Remove " + e.fileName);
                h.secondary.setOnClickListener(btn -> engine.removeEntry(e.id, false));
            }

            return v;
        }
    }

    // ------------------------------------------------------------------
    // Glass dialog helpers
    // ------------------------------------------------------------------

    private void trackDialog(Dialog d) {
        activeDialogs.add(d);
        d.setOnDismissListener(x -> activeDialogs.remove(d));
    }

    private LinearLayout dialogContent(int padDp) {
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(Math.max(0, padDp - 6));
        content.setPadding(pad, pad, pad, dp(6));
        return content;
    }

    private TextView text(String s, int color, float sizeSp, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean red) {
        final TextView b = text(label, red ? 0xFFFF8888 : 0xFFFFFFFF, 13, false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(red ? R.drawable.bg_glass_button_red : R.drawable.bg_glass_button);
        b.setClickable(true);
        b.setFocusable(true);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private AlertDialog glassDialog(View content) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(DialogFit.wrapGlassScroll(this, content))
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        trackDialog(dialog);
        dialog.show();
        return dialog;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
