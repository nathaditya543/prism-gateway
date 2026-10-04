package com.prism.gateway.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway settings bound from {@code prism.*} properties.
 *
 * @param providerBaseUrls optional per-provider base URL overrides (e.g. {@code prism.provider-base-urls.alpha}),
 *                         applied on top of the gateway config file; used by tests and alternate deployments
 */
@ConfigurationProperties(prefix = "prism")
public record PrismProperties(
        @DefaultValue("data/gateway_config.sample.json") String gatewayConfig,
        @DefaultValue("data/model_pricing.json") String pricingFile,
        @DefaultValue("data/seed_keys.json") String seedKeysFile,
        @DefaultValue("data/routing_eval.jsonl") String routingEvalFile,
        @DefaultValue("prism-admin-dev") String adminToken,
        Map<String, String> providerBaseUrls,
        @DefaultValue Upstream upstream,
        @DefaultValue Cache cache) {

    public PrismProperties {
        providerBaseUrls = providerBaseUrls == null ? Map.of() : Map.copyOf(providerBaseUrls);
    }

    /**
     * @param requestTimeoutMs        max wait for an upstream's response headers (non-streaming: the whole body)
     * @param streamIdleTimeoutMs     max gap between two upstream SSE events before the stream is declared dead
     * @param maxInFlightPerProvider  bulkhead size per provider (0 disables it); streams hold a slot until they end
     * @param queueTimeoutMs          how long a call waits for a free slot before failing over
     */
    public record Upstream(
            @DefaultValue("1000") int connectTimeoutMs,
            @DefaultValue("2500") int requestTimeoutMs,
            @DefaultValue("5000") int streamIdleTimeoutMs,
            @DefaultValue("16") int maxInFlightPerProvider,
            @DefaultValue("1000") int queueTimeoutMs) {
    }

    /**
     * @param maxEntriesPerScope oldest entries in a (key, model, context) scope are evicted past this size
     */
    public record Cache(
            @DefaultValue("1440") int ttlMinutes,
            @DefaultValue("500") int maxEntriesPerScope) {
    }
}
