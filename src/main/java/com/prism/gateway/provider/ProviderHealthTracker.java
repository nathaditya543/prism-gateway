package com.prism.gateway.provider;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Rolling 60-second record of every upstream attempt per provider, for the provider-health
 * endpoint and the console. Reporting only: routing still follows the configured chain.
 */
@Component
public class ProviderHealthTracker {

    static final long WINDOW_MS = 60_000;
    static final double ERROR_RATE_THRESHOLD = 0.5;

    private final Clock clock;
    private final Map<String, ArrayDeque<Sample>> samples = new ConcurrentHashMap<>();

    public ProviderHealthTracker(Clock clock) {
        this.clock = clock;
    }

    public void record(String provider, boolean success, long latencyMs, String outcome) {
        ArrayDeque<Sample> window = samples.computeIfAbsent(provider, p -> new ArrayDeque<>());
        synchronized (window) {
            long now = clock.millis();
            window.addLast(new Sample(now, success, latencyMs, outcome));
            evict(window, now);
        }
    }

    public Map<String, Snapshot> snapshot(Iterable<String> providers) {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        long now = clock.millis();
        for (String provider : providers) {
            List<Sample> copy;
            ArrayDeque<Sample> window = samples.computeIfAbsent(provider, p -> new ArrayDeque<>());
            synchronized (window) {
                evict(window, now);
                copy = new ArrayList<>(window);
            }
            result.put(provider, summarize(copy));
        }
        return result;
    }

    private static Snapshot summarize(List<Sample> window) {
        if (window.isEmpty()) {
            return new Snapshot(0, 0, 0.0, null, null, true, null);
        }
        long errors = window.stream().filter(s -> !s.success()).count();
        List<Long> latencies = window.stream().map(Sample::latencyMs).sorted().toList();
        long p95 = latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1));
        long avg = Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        double errorRate = (double) errors / window.size();
        Sample last = window.get(window.size() - 1);
        return new Snapshot(window.size(), errors, errorRate, avg, p95,
                errorRate < ERROR_RATE_THRESHOLD, last.outcome());
    }

    private static void evict(ArrayDeque<Sample> window, long now) {
        while (!window.isEmpty() && window.peekFirst().at() <= now - WINDOW_MS) {
            window.pollFirst();
        }
    }

    record Sample(long at, boolean success, long latencyMs, String outcome) {
    }

    public record Snapshot(int attempts, long errors, double errorRate, Long avgLatencyMs, Long p95LatencyMs,
                           boolean healthy, String lastOutcome) {
    }
}
