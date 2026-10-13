package com.spoongecko.browser;

import java.io.File;

/** One download entry: metadata + live progress. */
public class Download {

    public enum State { QUEUED, RUNNING, COMPLETE, FAILED, CANCELED }

    public final long id;
    public final String url;
    public final String filename;
    public final String mimeType;
    public final File targetFile;

    public volatile long totalBytes = -1L;    // -1 = unknown
    public volatile long bytesRead = 0L;
    public volatile State state = State.QUEUED;
    public volatile String error = null;
    public volatile boolean cancelRequested = false;

    public Download(long id, String url, String filename,
                    String mimeType, File targetFile) {
        this.id = id;
        this.url = url == null ? "" : url;
        this.filename = filename;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.targetFile = targetFile;
    }

    /** 0-100, or -1 when the total size is unknown. */
    public int progressPercent() {
        if (totalBytes <= 0L) return -1;
        long pct = (bytesRead * 100L) / totalBytes;
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return (int) pct;
    }

    public boolean isActive() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    public String displayName() {
        return filename == null || filename.isEmpty() ? "download" : filename;
    }
}
