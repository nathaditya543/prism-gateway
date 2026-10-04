package com.prism.gateway.usage;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Running per-key, per-calendar-month totals. Exists so the budget check is a single
 * primary-key read instead of a scan over request logs. Mapped here for schema creation only;
 * all writes go through {@link UsageStore}'s atomic SQL increments.
 */
@Entity
@Table(name = "usage_monthly", uniqueConstraints = @UniqueConstraint(columnNames = {"virtual_key", "usage_month"}))
public class UsageMonthlyEntity {

    /** {@code <virtual_key>|<yyyy-MM>} */
    @Id
    @Column(length = 160)
    private String id;

    @Column(name = "virtual_key", nullable = false, length = 128)
    private String virtualKey;

    @Column(name = "usage_month", nullable = false, length = 7)
    private String month;

    @Column(nullable = false)
    private long requests;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "cost_usd", nullable = false, precision = 24, scale = 12)
    private BigDecimal costUsd;

    @Column(name = "cache_hits", nullable = false)
    private long cacheHits;

    protected UsageMonthlyEntity() {
    }
}
