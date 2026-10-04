package com.prism.gateway.limits;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Exact sliding-window-log limiter: at most {@code limit} admissions in any rolling 60 seconds.
 *
 * <p>Check and record happen inside one per-key lock, so two concurrent requests can never both
 * see the last free slot (the read-then-write race the load test looks for). A token bucket was
 * rejected because it refills continuously and would admit an 11th request within a minute on a
 * 10 rpm key. Memory is O(limit) timestamps per key. State is in memory: single-instance only.
 */
@Component
public class SlidingWindowRateLimiter {

    static final long WINDOW_MS = 60_000;

    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision tryAcquire(String key, int limit) {
        ArrayDeque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            long now = clock.millis();
            while (!window.isEmpty() && window.peekFirst() <= now - WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() < limit) {
                window.addLast(now);
                return new Decision(true, limit - window.size(), 0);
            }
            long retryAfterMs = window.peekFirst() + WINDOW_MS - now;
            return new Decision(false, 0, Math.max(1, (retryAfterMs + 999) / 1000));
        }
    }

    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }
}
