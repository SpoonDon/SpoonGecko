package com.spoongecko.browser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dialog displaying saved Room bookmarks with quick save for the current session URL,
 * tap-to-open navigation, per-row deletion, and clear-all support.
 */
final class BookmarksDialog {

    private static final int LIST_MAX_DP = 360;
    private static final int LIST_MIN_DP = 96;
    private static final int CHROME_DP = 210;
    private static final float WIDTH_FRACTION = 0.9f;
    private static final float HEIGHT_FRACTION = 0.92f;

    private final Activity activity;
    private final BookmarkRepository repository;
    private Dialog dialog;
    private View.OnLayoutChangeListener windowWatcher;
    private boolean refitQueued;

    BookmarksDialog(Activity activity, BookmarkRepository repository) {
        this.activity = activity;
        this.repository = repository;
    }

    void show(final String currentUrl, final String currentTitle,
              final Consumer<String> onNavigate) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;

        final Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_bookmarks);
        final Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.setLayout(dialogWidthPx(), WindowManager.LayoutParams.WRAP_CONTENT);
        }

        final View saveCurrentRow = d.findViewById(R.id.btn_bookmark_current);
        final TextView saveCurrentSubtitle = d.findViewById(R.id.bookmark_current_subtitle);
        final ListView list = d.findViewById(R.id.dlg_list);
        final TextView empty = d.findViewById(R.id.dlg_empty);
        final TextView neg = d.findViewById(R.id.dlg_negative);
        final TextView pos = d.findViewById(R.id.dlg_positive);

        final boolean canBookmarkCurrent = BookmarkRepository.isBookmarkable(currentUrl, currentTitle);
        final BookmarkAdapter adapter = new BookmarkAdapter();
        list.setAdapter(adapter);

        final Runnable updateUiState = () -> {
            final boolean none = adapter.getCount() == 0;
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
            pos.setEnabled(!none);
            pos.setAlpha(none ? 0.4f : 1f);
            if (canBookmarkCurrent && !repository.isCachedBookmarked(currentUrl)) {
                saveCurrentRow.setVisibility(View.VISIBLE);
                saveCurrentSubtitle.setText(SuggestionRanker.shortUrl(currentUrl));
            } else {
                saveCurrentRow.setVisibility(View.GONE);
            }
            if (!none) {
                fitListHeight(list, adapter);
            }
        };

        final Runnable reload = () -> repository.loadAll(items -> {
            if (dialog != d || activity.isFinishing() || activity.isDestroyed()) return;
            adapter.setItems(items);
            updateUiState.run();
        });

        saveCurrentRow.setOnClickListener(v -> repository.saveBookmark(currentUrl, currentTitle, saved -> {
            if (dialog != d || activity.isFinishing() || activity.isDestroyed()) return;
            if (saved) {
                Toast.makeText(activity, "Bookmarked", Toast.LENGTH_SHORT).show();
                reload.run();
            }
        }));

        list.setOnItemClickListener((parent, view, position, id) -> {
            final BookmarkEntity item = adapter.getItem(position);
            if (item != null && item.url != null && !item.url.isEmpty()) {
                onNavigate.accept(item.url);
                d.dismiss();
            }
        });

        adapter.setOnDelete(item -> {
            final int first = list.getFirstVisiblePosition();
            final View firstRow = list.getChildAt(0);
            final int offset = firstRow == null ? 0 : firstRow.getTop();
            adapter.removeItem(item);
            repository.deleteBookmark(item, null);
            updateUiState.run();
            if (adapter.getCount() > 0) {
                list.setSelectionFromTop(Math.min(first, adapter.getCount() - 1), offset);
            }
        });

        neg.setOnClickListener(v -> d.dismiss());
        pos.setOnClickListener(v -> {
            if (adapter.getCount() == 0) return;
            adapter.setItems(new ArrayList<>());
            repository.clearAll(null);
            updateUiState.run();
            Toast.makeText(activity, "Bookmarks cleared", Toast.LENGTH_SHORT).show();
        });

        d.setOnDismissListener(x -> {
            if (dialog == d) dialog = null;
            stopFollowingWindowSize();
        });

        dialog = d;
        reload.run();
        d.show();
        followWindowSize(d, list, adapter);
    }

    void dismiss() {
        final Dialog d = dialog;
        dialog = null;
        stopFollowingWindowSize();
        if (d != null) {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private int windowWidthPx() {
        final int w = activity.getWindow().getDecorView().getWidth();
        return w > 0 ? w : activity.getResources().getDisplayMetrics().widthPixels;
    }

    private int windowHeightPx() {
        final int h = activity.getWindow().getDecorView().getHeight();
        return h > 0 ? h : activity.getResources().getDisplayMetrics().heightPixels;
    }

    private int dialogWidthPx() {
        return (int) (windowWidthPx() * WIDTH_FRACTION);
    }

    private int maxListHeightPx() {
        final int availH = Math.min((int) (windowHeightPx() * HEIGHT_FRACTION),
                DialogFit.visibleBounds(activity).height() - dp(16));
        final int room = availH - dp(CHROME_DP);
        return Math.max(dp(LIST_MIN_DP), Math.min(dp(LIST_MAX_DP), room));
    }

    private void followWindowSize(final Dialog d, final ListView list,
                                  final BookmarkAdapter adapter) {
        stopFollowingWindowSize();
        final View decor = activity.getWindow().getDecorView();
        final View.OnLayoutChangeListener watcher = (v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) == (or - ol) && (b - t) == (ob - ot)) return;
            if (refitQueued) return;
            refitQueued = true;
            v.post(() -> {
                refitQueued = false;
                if (dialog == d) fitToWindow(d, list, adapter);
            });
        };
        windowWatcher = watcher;
        decor.addOnLayoutChangeListener(watcher);
    }

    private void stopFollowingWindowSize() {
        final View.OnLayoutChangeListener watcher = windowWatcher;
        windowWatcher = null;
        refitQueued = false;
        if (watcher != null) {
            activity.getWindow().getDecorView().removeOnLayoutChangeListener(watcher);
        }
    }

    private void fitToWindow(Dialog d, ListView list, BookmarkAdapter adapter) {
        if (!d.isShowing()) return;
        final Window w = d.getWindow();
        if (w != null) w.setLayout(dialogWidthPx(), WindowManager.LayoutParams.WRAP_CONTENT);
        if (list.getVisibility() == View.VISIBLE && adapter.getCount() > 0) {
            fitListHeight(list, adapter);
        }
    }

    private void fitListHeight(ListView list, BookmarkAdapter adapter) {
        final int max = maxListHeightPx();
        final int listW = Math.max(dp(120), dialogWidthPx() - dp(32));
        final int widthSpec = View.MeasureSpec.makeMeasureSpec(listW, View.MeasureSpec.EXACTLY);
        int total = list.getPaddingTop() + list.getPaddingBottom();
        View rowView = null;
        for (int i = 0; i < adapter.getCount() && total < max; i++) {
            rowView = adapter.getView(i, rowView, list);
            rowView.measure(widthSpec, View.MeasureSpec.UNSPECIFIED);
            total += rowView.getMeasuredHeight();
        }
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        final int target = total == 0 ? max : Math.min(total, max);
        if (lp.height != target) {
            lp.height = target;
            list.setLayoutParams(lp);
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private static final class RowViewHolder {
        final TextView title;
        final TextView sub;
        final TextView time;
        final View delete;
        BookmarkEntity bound;

        RowViewHolder(View v) {
            this.title = v.findViewById(R.id.row_title);
            this.sub = v.findViewById(R.id.row_subtitle);
            this.time = v.findViewById(R.id.row_time);
            this.delete = v.findViewById(R.id.row_delete);
        }
    }

    private final class BookmarkAdapter extends BaseAdapter {
        private List<BookmarkEntity> items = new ArrayList<>();
        private Consumer<BookmarkEntity> onDelete;

        void setItems(List<BookmarkEntity> newItems) {
            items = newItems != null ? new ArrayList<>(newItems) : new ArrayList<>();
            notifyDataSetChanged();
        }

        void removeItem(BookmarkEntity target) {
            if (target == null) return;
            for (int i = 0; i < items.size(); i++) {
                final BookmarkEntity candidate = items.get(i);
                if (candidate.id == target.id && target.url.equals(candidate.url)) {
                    items.remove(i);
                    notifyDataSetChanged();
                    return;
                }
            }
        }

        void setOnDelete(Consumer<BookmarkEntity> onDelete) {
            this.onDelete = onDelete;
        }

        @Override public int getCount() { return items.size(); }
        @Override public BookmarkEntity getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override
        public View getView(int position, View cv, ViewGroup parent) {
            View v = cv;
            RowViewHolder holder = v != null && v.getTag() instanceof RowViewHolder
                    ? (RowViewHolder) v.getTag() : null;
            if (holder == null) {
                v = LayoutInflater.from(activity).inflate(R.layout.row_bookmark, parent, false);
                holder = new RowViewHolder(v);
                final RowViewHolder h = holder;
                holder.delete.setOnClickListener(x -> {
                    if (onDelete != null && h.bound != null) {
                        onDelete.accept(h.bound);
                    }
                });
                v.setTag(holder);
            }
            final BookmarkEntity item = items.get(position);
            holder.bound = item;
            final String shownUrl = SuggestionRanker.shortUrl(item.url);
            holder.title.setText(item.title.trim().isEmpty() ? shownUrl : item.title);
            holder.sub.setText(shownUrl);
            holder.time.setText(item.createdAt > 0
                    ? DateFormat.getDateFormat(activity).format(new Date(item.createdAt))
                    : "");
            return v;
        }
    }
}
