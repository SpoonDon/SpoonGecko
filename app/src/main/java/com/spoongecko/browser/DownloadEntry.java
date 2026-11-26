package com.spoongecko.browser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Persistent record of a single download managed by {@link DownloadEngine}.
 */
final class DownloadEntry {

    enum Status {
        QUEUED,
        RUNNING,
        PAUSED,
        SAVING,
        COMPLETED,
        FAILED,
        CANCELLED;

        boolean isActive() {
            return this == QUEUED || this == RUNNING || this == SAVING;
        }

        boolean isInProgressOrPaused() {
            return this == QUEUED || this == RUNNING || this == PAUSED || this == SAVING;
        }

        static Status fromString(String s) {
            if (s != null) {
                for (Status st : values()) {
                    if (st.name().equalsIgnoreCase(s)) return st;
                }
            }
            return FAILED;
        }
    }

    final long id;
    final String url;
    final String referrer;
    String mimeType;
    String dispositionHint;
    String fileName;
    long bytesDone;
    long totalBytes;
    long speedBytesPerSec; // transient live speed
    Status status;
    String errorMessage;
    String contentUri;
    String localPath;
    String eTag;
    boolean resumable;
    final long createdAt;
    long updatedAt;

    DownloadEntry(long id, String url, String referrer, String mimeType,
                  String dispositionHint, String fileName, long totalBytes,
                  boolean resumable, long createdAt) {
        this.id = id;
        this.url = url == null ? "" : url;
        this.referrer = referrer;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.dispositionHint = dispositionHint;
        this.fileName = fileName == null || fileName.isEmpty() ? "download" : fileName;
        this.totalBytes = totalBytes;
        this.resumable = resumable;
        this.status = Status.QUEUED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    DownloadEntry copy() {
        final DownloadEntry c = new DownloadEntry(
                id, url, referrer, mimeType, dispositionHint, fileName, totalBytes, resumable, createdAt);
        c.bytesDone = bytesDone;
        c.speedBytesPerSec = speedBytesPerSec;
        c.status = status;
        c.errorMessage = errorMessage;
        c.contentUri = contentUri;
        c.localPath = localPath;
        c.eTag = eTag;
        c.updatedAt = updatedAt;
        return c;
    }

    int progressPercent() {
        if (status == Status.COMPLETED) return 100;
        if (totalBytes <= 0 || bytesDone <= 0) return 0;
        return (int) Math.min(100, (bytesDone * 100L) / totalBytes);
    }

    boolean canPause() {
        return (status == Status.RUNNING || status == Status.QUEUED) && resumable;
    }

    boolean canResume() {
        return status == Status.PAUSED && resumable;
    }

    boolean canRetry() {
        return (status == Status.FAILED || status == Status.CANCELLED) && isHttpOrData();
    }

    boolean canCancel() {
        return status == Status.RUNNING || status == Status.QUEUED || status == Status.PAUSED;
    }

    boolean isHttpOrData() {
        final String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || DataUri.isDataUri(url);
    }

    String hostLabel() {
        if (DataUri.isDataUri(url)) return "Embedded data";
        if (url.toLowerCase(Locale.ROOT).startsWith("blob:")) return "Page blob";
        final String host = LocalAddress.hostOf(url);
        return host != null && !host.isEmpty() ? host : url;
    }

    String extensionBadge() {
        final String ext = DownloadNames.extensionOf(fileName);
        if (ext.isEmpty()) return "FILE";
        return ext.length() > 4 ? ext.substring(0, 4).toUpperCase(Locale.ROOT)
                : ext.toUpperCase(Locale.ROOT);
    }

    JSONObject toJson() throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("url", url);
        if (referrer != null) o.put("referrer", referrer);
        o.put("mimeType", mimeType);
        if (dispositionHint != null) o.put("dispositionHint", dispositionHint);
        o.put("fileName", fileName);
        o.put("bytesDone", bytesDone);
        o.put("totalBytes", totalBytes);
        o.put("status", status.name());
        if (errorMessage != null) o.put("errorMessage", errorMessage);
        if (contentUri != null) o.put("contentUri", contentUri);
        if (localPath != null) o.put("localPath", localPath);
        if (eTag != null) o.put("eTag", eTag);
        o.put("resumable", resumable);
        o.put("createdAt", createdAt);
        o.put("updatedAt", updatedAt);
        return o;
    }

    static DownloadEntry fromJson(JSONObject o) {
        if (o == null) return null;
        final long id = o.optLong("id", -1);
        if (id <= 0) return null;
        final String url = o.optString("url", "");
        final String referrer = o.has("referrer") ? o.optString("referrer", null) : null;
        final String mime = o.optString("mimeType", "application/octet-stream");
        final String disp = o.has("dispositionHint") ? o.optString("dispositionHint", null) : null;
        final String name = o.optString("fileName", "download");
        final long total = o.optLong("totalBytes", -1L);
        final boolean resumable = o.optBoolean("resumable", false);
        final long created = o.optLong("createdAt", System.currentTimeMillis());

        final DownloadEntry e = new DownloadEntry(
                id, url, referrer, mime, disp, name, total, resumable, created);
        e.bytesDone = Math.max(0L, o.optLong("bytesDone", 0L));
        e.status = Status.fromString(o.optString("status", Status.FAILED.name()));
        e.errorMessage = o.has("errorMessage") ? o.optString("errorMessage", null) : null;
        e.contentUri = o.has("contentUri") ? o.optString("contentUri", null) : null;
        e.localPath = o.has("localPath") ? o.optString("localPath", null) : null;
        e.eTag = o.has("eTag") ? o.optString("eTag", null) : null;
        e.updatedAt = o.optLong("updatedAt", created);
        return e;
    }
}
