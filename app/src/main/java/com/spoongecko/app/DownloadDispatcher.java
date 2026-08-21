package com.spoongecko.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;

import java.util.Locale;

public final class DownloadDispatcher {

    private DownloadDispatcher() {}

    public static boolean isExternalMode(Context context) {
        if (context == null) return false;
        String mode = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getString(Prefs.KEY_DOWNLOAD_MODE, Prefs.DOWNLOAD_MODE_NATIVE);
        return Prefs.DOWNLOAD_MODE_EXTERNAL.equals(mode);
    }

    public static boolean interceptNavigation(Context context, GeckoSession session, String uri) {
        if (context == null || uri == null) return false;

        if (isMagnet(uri)) {
            if (!isExternalMode(context)) {
                Toast.makeText(context, R.string.magnet_requires_external, Toast.LENGTH_LONG).show();
                return true;
            }
            openExternal(context, uri, null, null);
            return true;
        }

        if (isTorrentLink(uri) && context instanceof Activity) {
            Activity activity = (Activity) context;
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.torrent_title)
                    .setItems(new String[]{
                            activity.getString(R.string.torrent_open_external),
                            activity.getString(R.string.torrent_download_native)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            openExternal(activity, uri, null, null);
                        } else {
                            DownloadManager.downloadUrlNative(
                                    activity, MainActivity.getGeckoRuntime(), uri);
                        }
                    })
                    .show();
            return true;
        }

        return false;
    }

    public static boolean openExternal(Context context, String uri, String mime, String filename) {
        if (context == null || uri == null || uri.isEmpty()) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri data = Uri.parse(uri);
            if (mime != null && !mime.isEmpty() && !"application/octet-stream".equals(mime)) {
                intent.setDataAndType(data, mime);
            } else {
                intent.setData(data);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with)));
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_app_for_download, Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            Toast.makeText(context, R.string.no_app_for_download, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static boolean isMagnet(String uri) {
        return uri.toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    private static boolean isTorrentLink(String uri) {
        String path = Uri.parse(uri).getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".torrent");
    }
}
