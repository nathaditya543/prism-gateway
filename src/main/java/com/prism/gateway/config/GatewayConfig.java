package com.prism.gateway.config;

import java.util.List;
import java.util.Map;

/**
 * Provider registry, model aliases and retry policy, loaded from the gateway config file.
 */
public record GatewayConfig(
        List<ProviderConfig> providers,
        Map<String, AliasConfig> aliases,
        AutoRouteConfig auto,
        RetryPolicy retry) {

    /**
     * @param models models this provider serves; when empty, the provider serves every priced model
     *               named {@code <provider>-*} (the mock providers' convention)
     */
    public record ProviderConfig(String name, String baseUrl, String apiKey, List<String> models) {

        /** Never print the API key. */
        @Override
        public String toString() {
            return "ProviderConfig[name=" + name + ", baseUrl=" + baseUrl + "]";
        }
    }

    public record AliasConfig(String alias, String primary, List<String> fallbacks) {
    }

    /** Which alias the {@code auto} router sends simple and complex prompts to. */
    public record AutoRouteConfig(String simpleAlias, String complexAlias) {
    }

    public record RetryPolicy(int maxAttempts, long initialBackoffMs, double backoffMultiplier) {

        public long backoffBeforeAttempt(int attempt) {
            // attempt is 1-based; no wait before the first attempt
            if (attempt <= 1) {
                return 0;
            }
            return (long) (initialBackoffMs * Math.pow(backoffMultiplier, attempt - 2));
        }
    }
}
