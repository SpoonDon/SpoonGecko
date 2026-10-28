package com.spoongecko.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Constructs and shows every dialog used by the browser: tab list, history,
 * exit confirmation, and the content-permission prompt. Each method is
 * self-contained; no shared state between dialogs.
 */
public final class BrowserDialogs {

    private final Activity activity;
    private final TabManager tabManager;
    private final HistoryStore history;

    public BrowserDialogs(Activity activity, TabManager tabManager, HistoryStore history) {
        this.activity = activity;
        this.tabManager = tabManager;
        this.history = history;
    }

    // ------------------------------------------------------------------
    // Tab list
    // ------------------------------------------------------------------
    public void showTabList(Consumer<Integer> onTabSelected, Runnable onNewTab) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_list);
        styleDialog(d, 0.9f);

        TextView title = d.findViewById(R.id.dlg_title);
        title.setText("Tabs");
        ListView list = d.findViewById(R.id.dlg_list);
        TextView neg = d.findViewById(R.id.dlg_negative);
        TextView pos = d.findViewById(R.id.dlg_positive);

        TabListAdapter adapter = new TabListAdapter(d, list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos1, id) -> {
            onTabSelected.accept(pos1);
            d.dismiss();
        });

        neg.setText("Close");
        neg.setOnClickListener(v -> d.dismiss());
        pos.setText("New tab");
        pos.setOnClickListener(v -> { onNewTab.run(); d.dismiss(); });

        d.show();
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------
    public void showHistory(Consumer<String> onNavigate) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_list);
        styleDialog(d, 0.9f);

        TextView title = d.findViewById(R.id.dlg_title);
        title.setText("History");
        ListView list = d.findViewById(R.id.dlg_list);
        TextView neg = d.findViewById(R.id.dlg_negative);
        TextView pos = d.findViewById(R.id.dlg_positive);

        List<HistoryStore.Entry> entries = history.recent(100);
        ItemAdapter adapter = new ItemAdapter(d, entries);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos1, id) -> {
            onNavigate.accept(adapter.getItem(pos1).url);
            d.dismiss();
        });

        neg.setText("Close");
        neg.setOnClickListener(v -> d.dismiss());
        pos.setText("Clear");
        pos.setOnClickListener(v -> {
            history.clear();
            adapter.setData(new ArrayList<>());
            d.dismiss();
        });

        d.show();
    }

    // ------------------------------------------------------------------
    // Exit
    // ------------------------------------------------------------------
    public Dialog showExitConfirmation(Runnable onConfirmExit) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_exit);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        TextView cancel = d.findViewById(R.id.btn_cancel);
        TextView exit = d.findViewById(R.id.btn_exit);
        cancel.setOnClickListener(v -> d.dismiss());
        exit.setOnClickListener(v -> { d.dismiss(); onConfirmExit.run(); });
        d.show();
        return d;
    }

    // ------------------------------------------------------------------
    // Content permission
    // ------------------------------------------------------------------
    public void showContentPermissionDialog(String uri, int type, GeckoResult<Integer> result) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            return;
        }

        String host;
        try { host = new java.net.URI(uri).getHost(); }
        catch (Exception e) { host = uri; }
        if (host == null) host = uri;

        String what;
        switch (type) {
            case GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION:
                what = "your location"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION:
                what = "notifications"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE:
                what = "play audio"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE:
                what = "play video"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE:
                what = "store data on this device"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                what = "use DRM"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS:
                what = "access first-party cookies"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_TRACKING:
                what = "disable tracking protection"; break;
            case GeckoSession.PermissionDelegate.PERMISSION_XR:
                what = "use WebXR"; break;
            default:
                what = "this permission"; break;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Permission request")
                .setMessage(host + " wants to use " + what + ".")
                .setPositiveButton("Allow", (dlg, w2) -> result.complete(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW))
                .setNegativeButton("Block", (dlg, w2) -> result.complete(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY))
                .setOnCancelListener(dlg -> result.complete(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY))
                .show();
    }

    // ------------------------------------------------------------------
    // Shared dialog styling
    // ------------------------------------------------------------------
    private void styleDialog(Dialog d, float widthFraction) {
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(0.7f);
        w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * widthFraction),
                WindowManager.LayoutParams.WRAP_CONTENT);

        TextView neg = d.findViewById(R.id.dlg_negative);
        TextView pos = d.findViewById(R.id.dlg_positive);
        TextView title = d.findViewById(R.id.dlg_title);
        if (title != null) title.setTextColor(0xFFFFFFFF);
        if (neg != null) {
            neg.setTextColor(0xFFFFFFFF);
            neg.setBackgroundResource(R.drawable.bg_glass_button);
        }
        if (pos != null) {
            pos.setTextColor(0xFFFFFFFF);
            pos.setBackgroundResource(R.drawable.bg_glass_button);
        }
    }

    // ------------------------------------------------------------------
    // Adapters
    // ------------------------------------------------------------------
    private final class TabListAdapter extends BaseAdapter {
        private final Dialog owner;
        private final ListView list;

        TabListAdapter(Dialog owner, ListView list) { this.owner = owner; this.list = list; }

        @Override public int getCount() { return tabManager.count(); }
        @Override public Tab getItem(int p) { return tabManager.getTabs().get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv != null ? cv
                    : owner.getLayoutInflater().inflate(R.layout.row_tab, parent, false);
            Tab t = getItem(pos);
            TextView title = v.findViewById(R.id.row_title);
            TextView sub = v.findViewById(R.id.row_subtitle);
            TextView close = v.findViewById(R.id.row_close);
            title.setText(t.title == null || t.title.isEmpty() ? "New tab" : t.title);
            title.setTextColor(0xFFFFFFFF);
            sub.setText(t.url.isEmpty() ? "(empty)" : t.url);
            sub.setTextColor(0x99FFFFFF);
            close.setTextColor(0x99FFFFFF);
            close.setOnClickListener(x -> {
                int idx = tabManager.getTabs().indexOf(t);
                if (idx >= 0) tabManager.closeTab(idx);
                notifyDataSetChanged();
            });
            v.setBackgroundColor(pos == tabManager.getActiveIndex()
                    ? 0x22FFFFFF : Color.TRANSPARENT);
            return v;
        }
    }

    private static final class ItemAdapter extends BaseAdapter {
        private final Dialog owner;
        private List<HistoryStore.Entry> data;

        ItemAdapter(Dialog owner, List<HistoryStore.Entry> d) { this.owner = owner; this.data = d; }

        void setData(List<HistoryStore.Entry> d) { data = d; notifyDataSetChanged(); }

        @Override public int getCount() { return data.size(); }
        @Override public HistoryStore.Entry getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv != null ? cv
                    : owner.getLayoutInflater().inflate(R.layout.row_item, parent, false);
            HistoryStore.Entry e = data.get(pos);
            TextView title = v.findViewById(R.id.row_title);
            TextView sub = v.findViewById(R.id.row_subtitle);
            title.setText(e.title == null || e.title.isEmpty() ? e.url : e.title);
            title.setTextColor(0xFFFFFFFF);
            sub.setText(e.url);
            sub.setTextColor(0x66FFFFFF);
            return v;
        }
    }
}
