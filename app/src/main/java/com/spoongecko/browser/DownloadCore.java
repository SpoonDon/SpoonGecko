package com.spoongecko.browser;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The transfer itself: copy a stream into storage, with progress, pause,
 * cancellation, range parsing and cleanup.
 */
final class DownloadCore {

    private DownloadCore() {}

    /** Where the bytes go. */
    interface Sink {
        OutputStream open() throws IOException;
        /** Makes the file visible; returns the name it ended up with. */
        String commit() throws IOException;
        /** Removes whatever was written. Must be safe to call at any time, any number of times. */
        void abort();
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }

    static final class PausedException extends IOException {
        final long bytesDone;

        PausedException(long bytesDone) {
            super("Paused");
            this.bytesDone = bytesDone;
        }
    }

    static final class ContentRange {
        final long start;
        final long end;
        final long total; // -1 if '*'

        ContentRange(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.total = total;
        }
    }

    static final class Result {
        final long bytes;
        final String finalName;

        Result(long bytes, String finalName) {
            this.bytes = bytes;
            this.finalName = finalName;
        }
    }

    /** Limits how often progress is reported (each report becomes a notification/UI update). */
    static final class Throttle {
        private final long minIntervalMs;
        private final LongSupplier clock;
        private boolean first = true;
        private long last;

        Throttle(long minIntervalMs, LongSupplier clock) {
            this.minIntervalMs = minIntervalMs;
            this.clock = clock;
        }

        boolean shouldReport() {
            final long now = clock.getAsLong();
            if (first || now - last >= minIntervalMs) {
                first = false;
                last = now;
                return true;
            }
            return false;
        }
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    static Result run(InputStream in, Sink sink, long total, AtomicBoolean cancelled,
                      Throttle throttle, Progress progress) throws IOException {
        return run(in, sink, 0L, total, null, cancelled, true, throttle, progress);
    }

    /**
     * @param initialDone bytes already present when resuming (0 for fresh transfers)
     * @param total       announced full file length in bytes, or <= 0 if unknown
     * @param paused      optional flag; stops transfer cleanly keeping partial file
     * @param cancelled   optional flag; stops transfer and aborts sink
     * @param abortOnError whether to call {@link Sink#abort()} if an I/O error occurs
     */
    static Result run(InputStream in, Sink sink, long initialDone, long total,
                      AtomicBoolean paused, AtomicBoolean cancelled, boolean abortOnError,
                      Throttle throttle, Progress progress) throws IOException {
        OutputStream out = null;
        boolean committed = false;
        boolean preservePartial = false;
        long done = Math.max(0L, initialDone);
        try {
            out = sink.open();
            final byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                if (cancelled != null && cancelled.get()) throw new CancelledException();
                if (paused != null && paused.get()) {
                    out.flush();
                    preservePartial = true;
                    throw new PausedException(done);
                }
                final int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buffer, 0, n);
                done += n;
                if (progress != null && throttle != null && throttle.shouldReport()) {
                    progress.onProgress(done, total);
                }
            }
            if (cancelled != null && cancelled.get()) throw new CancelledException();
            if (paused != null && paused.get()) {
                out.flush();
                preservePartial = true;
                throw new PausedException(done);
            }
            if (total > 0 && done < total) {
                if (!abortOnError) preservePartial = true;
                throw new IOException("The download ended early (" + done + " of " + total + " bytes)");
            }
            out.flush();
            out.close();
            out = null;
            final String name = sink.commit();
            committed = true;
            if (progress != null) progress.onProgress(done, total > 0 ? total : done);
            return new Result(done, name);
        } catch (CancelledException e) {
            preservePartial = false;
            throw e;
        } catch (PausedException e) {
            preservePartial = true;
            throw e;
        } catch (IOException | RuntimeException e) {
            if (cancelled != null && cancelled.get()) {
                preservePartial = false;
            } else if (paused != null && paused.get()) {
                preservePartial = true;
            } else if (!abortOnError) {
                preservePartial = true;
            }
            throw e;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (!committed && !preservePartial) sink.abort();
        }
    }

    /** Parses HTTP Content-Range headers such as {@code bytes 100-999/1000} or {@code bytes 100-999/*}. */
    static ContentRange parseContentRange(String header) {
        if (header == null) return null;
        final String s = header.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("bytes ")) return null;
        final String rest = s.substring(6).trim();
        final int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        final String rangePart = rest.substring(0, slash).trim();
        final String totalPart = rest.substring(slash + 1).trim();
        final int dash = rangePart.indexOf('-');
        if (dash <= 0) return null;
        try {
            final long start = Long.parseLong(rangePart.substring(0, dash).trim());
            final long end = Long.parseLong(rangePart.substring(dash + 1).trim());
            final long total = "*".equals(totalPart) ? -1L : Long.parseLong(totalPart);
            if (start < 0 || end < start) return null;
            return new ContentRange(start, end, total >= 0 ? total : -1L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
            // nothing useful to do
        }
    }
}
