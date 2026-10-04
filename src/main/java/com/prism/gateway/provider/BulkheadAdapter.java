package com.prism.gateway.provider;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Caps concurrent calls to one provider. Extra callers queue for up to {@code waitMs} instead of
 * piling connections onto a saturated upstream; if no slot frees up they get a SATURATED error,
 * which the invoker treats like an unavailable provider (fail over). A streaming call holds its slot
 * until the stream is closed.
 */
public class BulkheadAdapter implements ProviderAdapter {

    private final ProviderAdapter delegate;
    private final Semaphore slots;
    private final int maxInFlight;
    private final long waitMs;

    public BulkheadAdapter(ProviderAdapter delegate, int maxInFlight, long waitMs) {
        this.delegate = delegate;
        this.slots = new Semaphore(maxInFlight, true);
        this.maxInFlight = maxInFlight;
        this.waitMs = waitMs;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public JsonNode complete(ObjectNode body) throws UpstreamException {
        acquire();
        try {
            return delegate.complete(body);
        } finally {
            slots.release();
        }
    }

    @Override
    public UpstreamStream openStream(ObjectNode body) throws UpstreamException {
        acquire();
        try {
            UpstreamStream stream = delegate.openStream(body);
            stream.onClose(slots::release);
            return stream;
        } catch (UpstreamException | RuntimeException e) {
            slots.release();
            throw e;
        }
    }

    public int inFlight() {
        return maxInFlight - slots.availablePermits();
    }

    private void acquire() throws UpstreamException {
        try {
            if (!slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                throw new UpstreamException(UpstreamException.Kind.SATURATED, 0,
                        "all " + maxInFlight + " in-flight slots busy for " + waitMs + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(UpstreamException.Kind.SATURATED, 0, "interrupted waiting for a slot");
        }
    }
}
