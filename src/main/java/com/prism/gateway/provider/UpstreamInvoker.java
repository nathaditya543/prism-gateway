package com.prism.gateway.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.prism.gateway.catalog.ModelTarget;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.RetryPolicy;

import tools.jackson.databind.node.ObjectNode;

/**
 * Walks a target chain (primary, then fallbacks): retries transient errors on the same provider
 * with exponential backoff, fails over on down / rate-limited / timed-out providers, and stops
 * immediately on errors that are the caller's fault. See {@link UpstreamException#disposition()}.
 */
@Component
public class UpstreamInvoker {

    private static final Logger log = LoggerFactory.getLogger(UpstreamInvoker.class);

    private final ProviderRegistry registry;
    private final ProviderHealthTracker health;
    private final CircuitBreakers breakers;
    private final RetryPolicy retry;

    public UpstreamInvoker(ProviderRegistry registry, ProviderHealthTracker health, CircuitBreakers breakers,
                           GatewayConfig config) {
        this.registry = registry;
        this.health = health;
        this.breakers = breakers;
        this.retry = config.retry();
    }

    @FunctionalInterface
    public interface Call<T> {
        T call(ProviderAdapter adapter, ObjectNode body) throws UpstreamException;
    }

    /**
     * When every target in the chain failed only at the connection level (refused / reset) within one
     * pass, that looks like momentary saturation (e.g. a full accept backlog) rather than an outage,
     * so the whole chain is walked once more after a backoff. Timeouts and HTTP errors never trigger
     * this, which keeps the worst case for a genuinely slow chain bounded.
     */
    static final int MAX_CHAIN_PASSES = 2;

    /** A provider that succeeded this recently is considered up when it refuses a connection. */
    static final long RECENT_SUCCESS_MS = 2_000;

    public <T> Invocation<T> invoke(List<ModelTarget> targets, ObjectNode body, Call<T> call) {
        Attempts state = new Attempts();
        for (int pass = 1; pass <= MAX_CHAIN_PASSES; pass++) {
            if (pass > 1) {
                state.retries++;
                sleep(retry.backoffBeforeAttempt(2));
            }
            state.onlyConnectionErrors = true;
            int attempted = 0;
            for (int index = 0; index < targets.size(); index++) {
                ModelTarget target = targets.get(index);
                // Checked lazily, only when the chain reaches this target, so a half-open probe is never claimed
                // and then left unused.
                if (!breakers.allow(target.provider())) {
                    state.trail.add(target.label() + ":circuit_open");
                    continue;
                }
                attempted++;
                Invocation<T> served = tryTarget(target, index > 0, body, call, state);
                if (served != null) {
                    return served;
                }
            }
            if (attempted == 0) {
                // Every breaker is open: try the chain anyway rather than return a guaranteed 502.
                for (int index = 0; index < targets.size(); index++) {
                    Invocation<T> served = tryTarget(targets.get(index), index > 0, body, call, state);
                    if (served != null) {
                        return served;
                    }
                }
            }
            if (!state.onlyConnectionErrors) {
                break;
            }
        }
        throw new UpstreamFailure(state.last, false, state.retries, state.trail(), state.upstreamMs());
    }

    /** Up to {@code maxAttempts} on one target; null when the chain should move on. */
    private <T> Invocation<T> tryTarget(ModelTarget target, boolean fallback, ObjectNode body, Call<T> call,
                                        Attempts state) {
        ProviderAdapter adapter = registry.get(target.provider());
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            if (attempt > 1) {
                state.retries++;
                sleep(retry.backoffBeforeAttempt(attempt));
            }
            ObjectNode request = body.deepCopy();
            request.put("model", target.model());
            long start = System.nanoTime();
            try {
                T result = call.call(adapter, request);
                long elapsed = System.nanoTime() - start;
                state.upstreamNanos += elapsed;
                health.record(target.provider(), true, elapsed / 1_000_000, "ok");
                breakers.onSuccess(target.provider());
                state.trail.add(target.label() + ":ok");
                return new Invocation<>(result, target, fallback, state.retries, state.trail(), state.upstreamMs());
            } catch (UpstreamException e) {
                long elapsed = System.nanoTime() - start;
                state.upstreamNanos += elapsed;
                if (e.disposition() == UpstreamException.Disposition.FATAL) {
                    breakers.onSuccess(target.provider()); // it answered; the request was the problem
                } else if (e.kind() != UpstreamException.Kind.SATURATED) {
                    // a full bulkhead is our own back-pressure, not evidence the provider is unhealthy
                    health.record(target.provider(), false, elapsed / 1_000_000, e.shortCode());
                    breakers.onFailure(target.provider());
                }
                state.trail.add(target.label() + ":" + e.shortCode());
                state.last = e;
                boolean connectionLevel = e.kind() == UpstreamException.Kind.CONNECT
                        || e.kind() == UpstreamException.Kind.IO
                        || e.kind() == UpstreamException.Kind.SATURATED;
                UpstreamException.Disposition disposition = e.disposition();
                if (e.kind() == UpstreamException.Kind.CONNECT
                        && breakers.recentlySucceeded(target.provider(), RECENT_SUCCESS_MS)) {
                    // It answered moments ago, so it is up: a refused connection means its accept queue is
                    // momentarily full. Back off and retry rather than treat it as down.
                    disposition = UpstreamException.Disposition.RETRY;
                }
                log.debug("Upstream {} attempt {} failed: {} ({})", target.label(), attempt, e.shortCode(), disposition);
                if (disposition == UpstreamException.Disposition.FATAL) {
                    throw new UpstreamFailure(e, true, state.retries, state.trail(), state.upstreamMs());
                }
                if (disposition == UpstreamException.Disposition.FAILOVER) {
                    state.onlyConnectionErrors &= connectionLevel;
                    return null;
                }
                if (attempt == retry.maxAttempts()) {
                    state.onlyConnectionErrors &= connectionLevel;
                }
            }
        }
        return null;
    }

    /** Running totals across every attempt of one request. */
    private static final class Attempts {
        final List<String> trail = new ArrayList<>();
        int retries;
        long upstreamNanos;
        UpstreamException last;
        boolean onlyConnectionErrors;

        String trail() {
            return String.join(" -> ", trail);
        }

        long upstreamMs() {
            return upstreamNanos / 1_000_000;
        }
    }

    /**
     * Sleeps 50-150% of {@code millis}. Without jitter, every request refused in the same instant would
     * retry in the same instant and collide again.
     */
    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep((long) (millis * (0.5 + ThreadLocalRandom.current().nextDouble())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @param fallback true when a target other than the chain's primary served the request
     * @param attempts e.g. {@code alpha/alpha-small:503 -> beta/beta-small:ok}
     */
    public record Invocation<T>(T result, ModelTarget target, boolean fallback, int retries, String attempts,
                                long upstreamLatencyMs) {
    }

    /** Every target in the chain failed, or one failed in a way no other provider could fix. */
    public static class UpstreamFailure extends RuntimeException {

        private final UpstreamException lastError;
        private final boolean callerError;
        private final int retries;
        private final String attempts;
        private final long upstreamLatencyMs;

        UpstreamFailure(UpstreamException lastError, boolean callerError, int retries, String attempts,
                        long upstreamLatencyMs) {
            super(lastError == null ? "no upstream attempted" : lastError.getMessage(), lastError);
            this.lastError = lastError;
            this.callerError = callerError;
            this.retries = retries;
            this.attempts = attempts;
            this.upstreamLatencyMs = upstreamLatencyMs;
        }

        public UpstreamException lastError() { return lastError; }
        public boolean callerError() { return callerError; }
        public int retries() { return retries; }
        public String attempts() { return attempts; }
        public long upstreamLatencyMs() { return upstreamLatencyMs; }
    }
}
