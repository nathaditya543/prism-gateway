package com.prism.gateway.usage;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One row per data-plane request, including rejections. Prompt and response bodies are
 * deliberately not stored here (see README: privacy); the cache table holds the only copies.
 */
@Entity
@Table(name = "request_logs", indexes = {
        @Index(name = "idx_logs_key_time", columnList = "virtual_key, created_at"),
        @Index(name = "idx_logs_time", columnList = "created_at")
})
public class RequestLogEntity {

    @Id
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** Null when the caller presented no valid key; see {@link #keyHint}. */
    @Column(name = "virtual_key", length = 128)
    private String virtualKey;

    @Column(name = "key_hint", length = 32)
    private String keyHint;

    @Column(length = 64)
    private String team;

    @Column(name = "requested_model", length = 128)
    private String requestedModel;

    @Column(name = "resolved_provider", length = 64)
    private String resolvedProvider;

    @Column(name = "resolved_model", length = 128)
    private String resolvedModel;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "cost_usd", nullable = false, precision = 24, scale = 12)
    private BigDecimal costUsd = BigDecimal.ZERO;

    /** hit / miss / bypass / n/a (rejected before the cache was consulted). */
    @Column(length = 8)
    private String cache;

    @Column(name = "cache_similarity")
    private Double cacheSimilarity;

    @Column(nullable = false)
    private boolean fallback;

    @Column(nullable = false)
    private boolean stream;

    @Column(name = "route_tier", length = 32)
    private String routeTier;

    @Column(name = "route_reason", length = 1000)
    private String routeReason;

    @Column(nullable = false)
    private int retries;

    /** Compact trail of upstream attempts, e.g. {@code alpha/alpha-small:503 -> beta/beta-small:200}. */
    @Column(name = "attempts", length = 1000)
    private String attempts;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "upstream_latency_ms")
    private Long upstreamLatencyMs;

    @Column(name = "error_type", length = 64)
    private String errorType;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RequestLogEntity() {
    }

    public RequestLogEntity(String requestId, Instant createdAt) {
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public String getRequestId() { return requestId; }
    public String getVirtualKey() { return virtualKey; }
    public void setVirtualKey(String virtualKey) { this.virtualKey = virtualKey; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public String getRequestedModel() { return requestedModel; }
    public void setRequestedModel(String requestedModel) { this.requestedModel = requestedModel; }
    public String getResolvedProvider() { return resolvedProvider; }
    public void setResolvedProvider(String resolvedProvider) { this.resolvedProvider = resolvedProvider; }
    public String getResolvedModel() { return resolvedModel; }
    public void setResolvedModel(String resolvedModel) { this.resolvedModel = resolvedModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }
    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public String getCache() { return cache; }
    public void setCache(String cache) { this.cache = cache; }
    public Double getCacheSimilarity() { return cacheSimilarity; }
    public void setCacheSimilarity(Double cacheSimilarity) { this.cacheSimilarity = cacheSimilarity; }
    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    public String getRouteTier() { return routeTier; }
    public void setRouteTier(String routeTier) { this.routeTier = routeTier; }
    public String getRouteReason() { return routeReason; }
    public void setRouteReason(String routeReason) { this.routeReason = truncate(routeReason); }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public String getAttempts() { return attempts; }
    public void setAttempts(String attempts) { this.attempts = truncate(attempts); }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public Long getUpstreamLatencyMs() { return upstreamLatencyMs; }
    public void setUpstreamLatencyMs(Long upstreamLatencyMs) { this.upstreamLatencyMs = upstreamLatencyMs; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = truncate(errorMessage); }
    public Instant getCreatedAt() { return createdAt; }

    private static String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 997) + "..." : s;
    }
}
