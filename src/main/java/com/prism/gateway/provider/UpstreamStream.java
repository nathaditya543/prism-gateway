package com.prism.gateway.provider;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads an upstream SSE body one {@code data:} payload at a time, without buffering the stream.
 *
 * <p>A watchdog closes the connection if the upstream goes quiet for longer than the idle timeout,
 * which turns a hung provider into a prompt IOException instead of a hung client.
 */
public class UpstreamStream implements Closeable {

    private final InputStream body;
    private final BufferedReader reader;
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final AtomicBoolean idleTimedOut = new AtomicBoolean();
    private final ScheduledFuture<?> watchdog;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Runnable onClose;

    public UpstreamStream(InputStream body, ScheduledExecutorService scheduler, long idleTimeoutMs) {
        this.body = body;
        this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs);
        this.watchdog = scheduler.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastActivity.get() > idleNanos && idleTimedOut.compareAndSet(false, true)) {
                closeQuietly();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * @return the next event's data payload (e.g. a JSON chunk or {@code [DONE]}), or null at end of stream
     * @throws IOException on connection loss or idle timeout (see {@link #idleTimedOut()})
     */
    public String nextData() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lastActivity.set(System.nanoTime());
            if (line.startsWith("data:")) {
                return line.substring(5).trim();
            }
            // blank separators, comments (":"), "event:" and "id:" lines carry nothing we forward
        }
        if (idleTimedOut.get()) {
            throw new IOException("upstream stream idle timeout");
        }
        return null;
    }

    public boolean idleTimedOut() {
        return idleTimedOut.get();
    }

    /** Runs once, when the stream is closed (e.g. to free a bulkhead slot). */
    public void onClose(Runnable callback) {
        Runnable previous = onClose;
        onClose = previous == null ? callback : () -> {
            previous.run();
            callback.run();
        };
    }

    @Override
    public void close() {
        watchdog.cancel(false);
        closeQuietly();
        if (closed.compareAndSet(false, true) && onClose != null) {
            onClose.run();
        }
    }

    private void closeQuietly() {
        try {
            body.close();
        } catch (IOException ignored) {
            // already broken; nothing useful to do
        }
    }
}
