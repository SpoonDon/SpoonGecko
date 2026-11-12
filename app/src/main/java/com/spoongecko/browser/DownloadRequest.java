package com.spoongecko.browser;

import java.io.InputStream;

/**
 * Everything needed to start one download.
 *
 * Two sources:
 *   - stream mode ({@code stream != null}): the page's own response, already
 *     open and authenticated by Gecko. Works for POST results, blob: files and
 *     anything behind a login. It can only be read once, so it can't be retried.
 *   - address mode ({@code stream == null}): the file is fetched from
 *     {@code url} through Gecko's network stack (same cookies as the browser)
 *     or, for data: URIs, decoded locally. Can be retried.
 */
final class DownloadRequest {

    final String url;
    final String referrer;            // may be null
    final String mimeHint;            // may be null
    final String dispositionHint;     // Content-Disposition header, may be null
    final long length;                // bytes, or -1 if unknown
    final InputStream stream;         // null in address mode
    final Runnable onStreamDone;      // may be null; runs on the UI thread when the stream is no longer needed

    DownloadRequest(String url, String referrer, String mimeHint, String dispositionHint,
                    long length, InputStream stream, Runnable onStreamDone) {
        this.url = url == null ? "" : url;
        this.referrer = referrer;
        this.mimeHint = mimeHint;
        this.dispositionHint = dispositionHint;
        this.length = length;
        this.stream = stream;
        this.onStreamDone = onStreamDone;
    }

    static DownloadRequest forUrl(String url, String referrer) {
        return new DownloadRequest(url, referrer, null, null, -1, null, null);
    }

    boolean isRetryable() {
        return stream == null;
    }
}
