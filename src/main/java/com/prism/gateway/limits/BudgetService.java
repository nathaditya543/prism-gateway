package com.prism.gateway.limits;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.prism.gateway.keys.VirtualKey;
import com.prism.gateway.usage.UsageStore;

/**
 * Admission against the key's monthly budget (calendar month, UTC, reset-on-read), with in-flight
 * reservations so concurrent requests cannot all slip through on the same stale balance.
 *
 * <p>Under a per-key lock, a request is admitted only if {@code spent + reserved < budget}, where
 * {@code reserved} is the estimated cost of the key's requests still in flight. The admitted request
 * then reserves its own estimate until its real cost is recorded ({@link #release}). So:
 * <ul>
 *   <li>sequential requests behave exactly as before: admitted while spend is below the budget;</li>
 *   <li>a burst near the limit admits only as many requests as the remaining budget covers by estimate,
 *       instead of every request that arrives before the first one is billed;</li>
 *   <li>overshoot is bounded by roughly one request: the last admitted request's actual cost (its
 *       estimate is checked against the remaining budget only through the in-flight sum), plus any
 *       estimate error of the requests in flight. Streams follow the same rule; their final cost
 *       replaces the reservation when the stream ends.</li>
 * </ul>
 * Reservations live in memory, so this is exact for a single gateway instance.
 */
@Service
public class BudgetService {

    private final UsageStore usage;
    private final Map<String, BigDecimal> reserved = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public BudgetService(UsageStore usage) {
        this.usage = usage;
    }

    /** Read-only view for reporting (admin API, console). */
    public Status check(VirtualKey key) {
        BigDecimal spent = usage.monthSpend(key.key());
        return new Status(spent, key.monthlyBudgetUsd(), reservedFor(key.key()),
                spent.compareTo(key.monthlyBudgetUsd()) >= 0);
    }

    /**
     * Admits and reserves {@code estimate}, or returns a status with {@code exhausted = true} and no reservation.
     */
    public Admission admit(VirtualKey key, BigDecimal estimate) {
        synchronized (locks.computeIfAbsent(key.key(), k -> new Object())) {
            BigDecimal spent = usage.monthSpend(key.key());
            BigDecimal inFlight = reservedFor(key.key());
            boolean exhausted = spent.add(inFlight).compareTo(key.monthlyBudgetUsd()) >= 0;
            Status status = new Status(spent, key.monthlyBudgetUsd(), inFlight, exhausted);
            if (exhausted) {
                return new Admission(status, null);
            }
            reserved.merge(key.key(), estimate, BigDecimal::add);
            return new Admission(status, new Reservation(key.key(), estimate));
        }
    }

    /** Call exactly once per admitted request, after its real cost has been recorded. */
    public void release(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        synchronized (locks.computeIfAbsent(reservation.key(), k -> new Object())) {
            reserved.computeIfPresent(reservation.key(), (k, v) -> {
                BigDecimal left = v.subtract(reservation.amount());
                return left.signum() <= 0 ? null : left;
            });
        }
    }

    BigDecimal reservedFor(String key) {
        return reserved.getOrDefault(key, BigDecimal.ZERO);
    }

    /** @param reserved estimated cost of this key's requests currently in flight */
    public record Status(BigDecimal spent, BigDecimal budget, BigDecimal reserved, boolean exhausted) {
    }

    public record Admission(Status status, Reservation reservation) {

        public boolean admitted() {
            return reservation != null;
        }
    }

    public record Reservation(String key, BigDecimal amount) {
    }
}
