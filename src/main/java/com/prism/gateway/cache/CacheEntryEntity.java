package com.prism.gateway.cache;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * A cached successful completion. {@code scope} = virtual key + model tier + hash of the
 * conversation context, so entries can never be served across tenants, tiers or conversations.
 */
@Entity
@Table(name = "cache_entries", indexes = {
        @Index(name = "idx_cache_scope", columnList = "scope"),
        @Index(name = "idx_cache_key", columnList = "virtual_key")
})
public class CacheEntryEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "virtual_key", nullable = false, length = 128)
    private String virtualKey;

    /** The tier/alias the entry was created under (for {@code auto}, the tier it was routed to). */
    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false, length = 400)
    private String scope;

    @Column(nullable = false, length = 64)
    private String embedder;

    @Column(name = "prompt_text", length = 4000)
    private String promptText;

    @Lob
    @Column(nullable = false)
    private String vector;

    @Lob
    @Column(name = "response_json", nullable = false)
    private String responseJson;

    @Column(name = "served_by", length = 200)
    private String servedBy;

    @Column(name = "original_cost_usd", precision = 24, scale = 12)
    private BigDecimal originalCostUsd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    protected CacheEntryEntity() {
    }

    public CacheEntryEntity(String id, String virtualKey, String model, String scope, String embedder,
                            String promptText, String vector, String responseJson, String servedBy,
                            BigDecimal originalCostUsd, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.virtualKey = virtualKey;
        this.model = model;
        this.scope = scope;
        this.embedder = embedder;
        this.promptText = promptText != null && promptText.length() > 4000 ? promptText.substring(0, 4000) : promptText;
        this.vector = vector;
        this.responseJson = responseJson;
        this.servedBy = servedBy;
        this.originalCostUsd = originalCostUsd;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getVirtualKey() { return virtualKey; }
    public String getModel() { return model; }
    public String getScope() { return scope; }
    public String getEmbedder() { return embedder; }
    public String getPromptText() { return promptText; }
    public String getVector() { return vector; }
    public String getResponseJson() { return responseJson; }
    public String getServedBy() { return servedBy; }
    public BigDecimal getOriginalCostUsd() { return originalCostUsd; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getHitCount() { return hitCount; }
}
