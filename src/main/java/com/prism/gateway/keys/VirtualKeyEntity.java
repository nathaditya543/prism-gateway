package com.prism.gateway.keys;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "virtual_keys")
public class VirtualKeyEntity {

    @Id
    @Column(name = "virtual_key", length = 128)
    private String virtualKey;

    @Column(nullable = false, length = 64)
    private String team;

    @Column(name = "monthly_budget_usd", nullable = false, precision = 20, scale = 10)
    private BigDecimal monthlyBudgetUsd;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "tokens_per_minute")
    private Integer tokensPerMinute;

    /** Comma-separated aliases / model names. */
    @Column(name = "model_allowlist", nullable = false, length = 512)
    private String modelAllowlist;

    @Column(name = "cache_enabled", nullable = false)
    private boolean cacheEnabled;

    @Column(name = "cache_similarity_threshold")
    private Double cacheSimilarityThreshold;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VirtualKeyEntity() {
    }

    public VirtualKeyEntity(String virtualKey, Instant createdAt) {
        this.virtualKey = virtualKey;
        this.createdAt = createdAt;
        this.status = "active";
    }

    public VirtualKey toDomain() {
        return new VirtualKey(virtualKey, team, monthlyBudgetUsd, requestsPerMinute, tokensPerMinute,
                VirtualKey.parseAllowlist(modelAllowlist), cacheEnabled, cacheSimilarityThreshold,
                "active".equals(status), createdAt);
    }

    public String getVirtualKey() { return virtualKey; }
    public void setTeam(String team) { this.team = team; }
    public void setMonthlyBudgetUsd(BigDecimal v) { this.monthlyBudgetUsd = v; }
    public void setRequestsPerMinute(int v) { this.requestsPerMinute = v; }
    public void setTokensPerMinute(Integer v) { this.tokensPerMinute = v; }
    public void setModelAllowlist(String v) { this.modelAllowlist = v; }
    public void setCacheEnabled(boolean v) { this.cacheEnabled = v; }
    public void setCacheSimilarityThreshold(Double v) { this.cacheSimilarityThreshold = v; }
    public void setStatus(String status) { this.status = status; }
}
