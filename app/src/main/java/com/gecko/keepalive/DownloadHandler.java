package com.gecko.keepalive;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class DownloadHandler {

    public static void handleDownload(Context context, String url, String filename, String contentType, long contentLength) {
        if (url == null || url.isEmpty()) return;

        if (filename == null || filename.isEmpty() || filename.equals("download_file")) {
            filename = "download_" + System.currentTimeMillis();
        }

        if (url.startsWith("magnet:") || url.endsWith(".torrent") || "application/x-bittorrent".equals(contentType)) {
            openTorrent(context, url);
            return;
        }

        showDownloadDialog(context, url, filename, contentType, contentLength);
    }

    private static void showDownloadDialog(Context context, String url, String filename, String contentType, long contentLength) {
        String size = contentLength > 0 ? (contentLength / 1024) + " KB" : "Unknown size";
        new AlertDialog.Builder(context)
            .setTitle("Download File")
            .setMessage("File: " + filename + "\nSize: " + size + "\n\nChoose downloader:")
            .setPositiveButton("Native", (dialog, which) -> downloadNative(context, url, filename, contentType))
            .setNegativeButton("External", (dialog, which) -> downloadExternal(context, url))
            .setNeutralButton("Cancel", (dialog, which) -> dialog.dismiss())
            .show();
    }

    private static void downloadNative(Context context, String url, String filename, String contentType) {
        try {
            Context appContext = context.getApplicationContext();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(contentType);
            request.setTitle(filename);
            request.setDescription("Downloading via Spoon Gecko");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            
            DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(appContext, "Native download started...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context.getApplicationContext(), "Native download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void downloadExternal(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context.getApplicationContext(), "No external downloader found.", Toast.LENGTH_SHORT).show();
        }
    }

    private static void openTorrent(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context.getApplicationContext(), "No torrent app found. Install one to open magnet/torrent links.", Toast.LENGTH_LONG).show();
        }
    }
}
