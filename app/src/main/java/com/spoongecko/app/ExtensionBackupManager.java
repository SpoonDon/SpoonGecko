package com.spoongecko.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.WebExtension;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ExtensionBackupManager {

    private static final String TAG = "ExtensionBackup";

    public static final class BackupEntry {
        public final String id;
        public final String name;

        BackupEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private ExtensionBackupManager() {}

    public static boolean exportBackup(Context context, List<WebExtension> extensions, Uri destination) {
        try {
            JSONArray array = new JSONArray();
            for (WebExtension ext : extensions) {
                if (ext == null || ext.id == null) continue;
                JSONObject item = new JSONObject();
                item.put("id", ext.id);
                item.put("name", ExtensionController.getDisplayName(ext));
                array.put(item);
            }

            JSONObject root = new JSONObject();
            root.put("format", 1);
            root.put("app", "SpoonGecko");
            root.put("extensions", array);

            byte[] data = root.toString(2).getBytes(StandardCharsets.UTF_8);
            ContentResolver resolver = context.getContentResolver();
            try (OutputStream out = resolver.openOutputStream(destination)) {
                if (out == null) return false;
                out.write(data);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "exportBackup failed", e);
            return false;
        }
    }

    public static List<BackupEntry> parseBackup(Context context, Uri source) {
        try {
            String text = readAll(context.getContentResolver(), source);
            if (text == null) return null;

            JSONObject root = new JSONObject(text);
            JSONArray array = root.optJSONArray("extensions");
            if (array == null) return new ArrayList<>();

            List<BackupEntry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) continue;
                entries.add(new BackupEntry(id, item.optString("name", "")));
            }
            return entries;
        } catch (Exception e) {
            Log.e(TAG, "parseBackup failed", e);
            return null;
        }
    }

    public static String amoLatestUrl(String extensionId) {
        return "https://addons.mozilla.org/firefox/downloads/latest/"
                + Uri.encode(extensionId) + "/latest.xpi";
    }

    private static String readAll(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
