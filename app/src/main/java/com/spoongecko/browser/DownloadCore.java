package com.spoongecko.browser;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The transfer itself: copy a stream into storage, with progress, cancellation
 * and cleanup. No Android classes, so every failure path can be tested.
 *
 * Guarantees:
 *   - the input stream is always closed;
 *   - on ANY failure or cancellation the half-written file is removed
 *     ({@link Sink#abort()}), so no truncated file is ever left looking like a
 *     finished download;
 *   - a stream that ends before the announced length is a failure, not a
 *     shorter "successful" file.
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

    static final class Result {
        final long bytes;
        final String finalName;

        Result(long bytes, String finalName) {
            this.bytes = bytes;
            this.finalName = finalName;
        }
    }

    /** Limits how often progress is reported (each report becomes a notification update). */
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

    /**
     * @param total announced length in bytes, or a value <= 0 if unknown
     * @throws CancelledException when {@code cancelled} became true
     */
    static Result run(InputStream in, Sink sink, long total, AtomicBoolean cancelled,
                      Throttle throttle, Progress progress) throws IOException {
        OutputStream out = null;
        boolean committed = false;
        try {
            out = sink.open();
            final byte[] buffer = new byte[BUFFER_SIZE];
            long done = 0;
            while (true) {
                if (cancelled.get()) throw new CancelledException();
                final int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buffer, 0, n);
                done += n;
                if (progress != null && throttle != null && throttle.shouldReport()) {
                    progress.onProgress(done, total);
                }
            }
            if (cancelled.get()) throw new CancelledException();
            if (total > 0 && done < total) {
                throw new IOException("The download ended early (" + done + " of " + total + " bytes)");
            }
            out.flush();
            out.close();
            out = null;
            final String name = sink.commit();
            committed = true;
            return new Result(done, name);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (!committed) sink.abort();
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
