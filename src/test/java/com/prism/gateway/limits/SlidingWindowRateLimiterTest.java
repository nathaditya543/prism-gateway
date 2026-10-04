package com.prism.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.prism.gateway.MutableClock;

class SlidingWindowRateLimiterTest {

    @Test
    void admitsExactlyTheLimitThenRejects() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(new MutableClock());
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("k", 10).allowed()).isTrue();
        }
        SlidingWindowRateLimiter.Decision denied = limiter.tryAcquire("k", 10);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void windowSlidesRatherThanRefillingContinuously() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k", 10);
            clock.millis.addAndGet(1_000); // 10 admissions spread over 10s
        }
        // 50s after the first admission: a token bucket would have refilled; the window must not have.
        clock.millis.set(1_000_000 + 59_999);
        assertThat(limiter.tryAcquire("k", 10).allowed()).isFalse();
        // Once the first admission is 60s old, exactly one slot frees up.
        clock.millis.set(1_000_000 + 60_000);
        assertThat(limiter.tryAcquire("k", 10).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 10).allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(new MutableClock());
        assertThat(limiter.tryAcquire("a", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1).allowed()).isTrue();
    }

    @Test
    void neverOverAdmitsUnderConcurrency() throws Exception {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Clock.systemUTC());
        int threads = 64;
        int limit = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return limiter.tryAcquire("burst", limit).allowed();
            }));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Boolean> f : results) {
            admitted += f.get(10, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(admitted).isEqualTo(limit);
    }
}
