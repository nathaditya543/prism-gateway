package com.prism.gateway.usage;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Monthly usage counters. Every increment is a single {@code UPDATE ... SET x = x + ?} so concurrent
 * requests can never lose an update (no read-then-write). A month's row is created lazily; the
 * insert race between two first-of-the-month requests is resolved by the unique key and a retry.
 */
@Repository
public class UsageStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UsageStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String currentMonth() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    public void add(String virtualKey, long promptTokens, long completionTokens, BigDecimal cost, boolean cacheHit) {
        String month = currentMonth();
        int hit = cacheHit ? 1 : 0;
        if (increment(virtualKey, month, promptTokens, completionTokens, cost, hit) > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO usage_monthly (id, virtual_key, usage_month, requests, prompt_tokens, completion_tokens, cost_usd, cache_hits)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?)""",
                    virtualKey + "|" + month, virtualKey, month, promptTokens, completionTokens, cost, hit);
        } catch (DuplicateKeyException raced) {
            increment(virtualKey, month, promptTokens, completionTokens, cost, hit);
        }
    }

    private int increment(String key, String month, long p, long c, BigDecimal cost, int hit) {
        return jdbc.update("""
                UPDATE usage_monthly
                   SET requests = requests + 1,
                       prompt_tokens = prompt_tokens + ?,
                       completion_tokens = completion_tokens + ?,
                       cost_usd = cost_usd + ?,
                       cache_hits = cache_hits + ?
                 WHERE id = ?""", p, c, cost, hit, key + "|" + month);
    }

    /** Spend so far this calendar month (UTC). A new month simply has no row yet: reset-on-read. */
    public BigDecimal monthSpend(String virtualKey) {
        List<BigDecimal> rows = jdbc.queryForList(
                "SELECT cost_usd FROM usage_monthly WHERE id = ?", BigDecimal.class,
                virtualKey + "|" + currentMonth());
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }
}
