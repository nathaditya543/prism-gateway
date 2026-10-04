package com.prism.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.prism.gateway.keys.VirtualKey;
import com.prism.gateway.usage.UsageStore;

class BudgetServiceTest {

    /** Spend that the test controls; no database. */
    static final class FakeUsage extends UsageStore {
        volatile BigDecimal spent = BigDecimal.ZERO;

        FakeUsage() {
            super(null, java.time.Clock.systemUTC());
        }

        @Override
        public BigDecimal monthSpend(String virtualKey) {
            return spent;
        }
    }

    private static VirtualKey key(String budget) {
        return new VirtualKey("k", "t", new BigDecimal(budget), 1000, null, Set.of("fast"), false, null, true, Instant.EPOCH);
    }

    @Test
    void admitsWhileSpendIsBelowBudgetThenRejects() {
        FakeUsage usage = new FakeUsage();
        BudgetService budgets = new BudgetService(usage);
        BudgetService.Admission first = budgets.admit(key("1.00"), new BigDecimal("0.10"));
        assertThat(first.admitted()).isTrue();
        budgets.release(first.reservation());
        usage.spent = new BigDecimal("1.00");
        assertThat(budgets.admit(key("1.00"), new BigDecimal("0.10")).admitted()).isFalse();
    }

    @Test
    void inFlightReservationsCountAgainstTheBudget() {
        BudgetService budgets = new BudgetService(new FakeUsage());
        VirtualKey k = key("0.25");
        assertThat(budgets.admit(k, new BigDecimal("0.10")).admitted()).isTrue();  // 0.00 + 0.00 < 0.25
        assertThat(budgets.admit(k, new BigDecimal("0.10")).admitted()).isTrue();  // 0.00 + 0.10 < 0.25
        assertThat(budgets.admit(k, new BigDecimal("0.10")).admitted()).isTrue();  // 0.00 + 0.20 < 0.25
        BudgetService.Admission fourth = budgets.admit(k, new BigDecimal("0.10")); // 0.00 + 0.30 >= 0.25
        assertThat(fourth.admitted()).isFalse();
        assertThat(fourth.status().reserved()).isEqualByComparingTo("0.30");
    }

    @Test
    void releasingFreesTheReservation() {
        BudgetService budgets = new BudgetService(new FakeUsage());
        VirtualKey k = key("0.15");
        BudgetService.Admission a = budgets.admit(k, new BigDecimal("0.20"));
        assertThat(budgets.admit(k, new BigDecimal("0.20")).admitted()).isFalse();
        budgets.release(a.reservation());
        assertThat(budgets.reservedFor("k")).isEqualByComparingTo("0");
        assertThat(budgets.admit(k, new BigDecimal("0.20")).admitted()).isTrue();
    }

    @Test
    void concurrentBurstAdmitsOnlyWhatTheRemainingBudgetCovers() throws Exception {
        BudgetService budgets = new BudgetService(new FakeUsage());
        VirtualKey k = key("0.00001");
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return budgets.admit(k, new BigDecimal("0.00002")).admitted();
            }));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Boolean> f : results) {
            admitted += f.get(10, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(admitted).isEqualTo(1);
    }
}
