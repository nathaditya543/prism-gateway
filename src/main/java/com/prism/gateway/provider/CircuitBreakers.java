package com.prism.gateway.provider;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Degradation-based routing: one circuit breaker per provider.
 * <ul>
 *   <li><b>closed</b> - normal. {@value #FAILURE_THRESHOLD} consecutive failed attempts open it.</li>
 *   <li><b>open</b> - the provider is skipped in every chain for {@value #COOLDOWN_MS} ms, so a dead or
 *       slow provider stops costing each caller a timeout before failover.</li>
 *   <li><b>half-open</b> - after the cooldown exactly one request probes the provider; success closes the
 *       breaker (traffic returns to the primary at once), failure re-opens it for another cooldown.</li>
 * </ul>
 * If every provider in a chain is open, the chain is tried anyway: a long shot beats a guaranteed 502.
 */
@Component
public class CircuitBreakers {

    static final int FAILURE_THRESHOLD = 5;
    static final long COOLDOWN_MS = 5_000;

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final Clock clock;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakers(Clock clock) {
        this.clock = clock;
    }

    /** May this request use the provider? In half-open state only the first caller (the probe) gets a yes. */
    public boolean allow(String provider) {
        Breaker b = breaker(provider);
        synchronized (b) {
            switch (b.state) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (clock.millis() - b.openedAt >= COOLDOWN_MS) {
                        b.state = State.HALF_OPEN; // this caller is the probe
                        return true;
                    }
                    return false;
                default: // HALF_OPEN: a probe is already in flight
                    return false;
            }
        }
    }

    public void onSuccess(String provider) {
        Breaker b = breaker(provider);
        synchronized (b) {
            b.state = State.CLOSED;
            b.consecutiveFailures = 0;
            b.lastSuccessAt = clock.millis();
        }
    }

    /** Did the provider answer successfully within the last {@code withinMs}? */
    public boolean recentlySucceeded(String provider, long withinMs) {
        Breaker b = breaker(provider);
        synchronized (b) {
            return b.lastSuccessAt > 0 && clock.millis() - b.lastSuccessAt <= withinMs;
        }
    }

    public void onFailure(String provider) {
        Breaker b = breaker(provider);
        synchronized (b) {
            b.consecutiveFailures++;
            if (b.state == State.HALF_OPEN || b.consecutiveFailures >= FAILURE_THRESHOLD) {
                b.state = State.OPEN;
                b.openedAt = clock.millis();
            }
        }
    }

    public Map<String, State> states(Iterable<String> providers) {
        Map<String, State> result = new LinkedHashMap<>();
        for (String p : providers) {
            Breaker b = breaker(p);
            synchronized (b) {
                result.put(p, b.state);
            }
        }
        return result;
    }

    private Breaker breaker(String provider) {
        return breakers.computeIfAbsent(provider, p -> new Breaker());
    }

    private static final class Breaker {
        State state = State.CLOSED;
        int consecutiveFailures;
        long openedAt;
        long lastSuccessAt;
    }
}
