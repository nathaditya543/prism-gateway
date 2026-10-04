package com.prism.gateway.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the pack's data files (gateway config and price table) into typed beans.
 * Keys starting with {@code _} are documentation comments in the pack and are skipped.
 */
@Configuration
public class ConfigFiles {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public GatewayConfig gatewayConfig(PrismProperties props, ObjectMapper mapper) {
        JsonNode root = readJson(mapper, props.gatewayConfig());

        List<GatewayConfig.ProviderConfig> providers = new ArrayList<>();
        for (JsonNode p : root.path("providers")) {
            String name = p.path("name").asString();
            String envName = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            String baseUrl = firstNonBlank(
                    props.providerBaseUrls().get(name),
                    System.getenv("PRISM_PROVIDER_" + envName + "_BASE_URL"),
                    p.path("base_url").asString());
            String apiKey = firstNonBlank(
                    System.getenv("PRISM_PROVIDER_" + envName + "_API_KEY"),
                    p.path("api_key").asString());
            List<String> models = new ArrayList<>();
            p.path("models").forEach(m -> models.add(m.asString()));
            providers.add(new GatewayConfig.ProviderConfig(name, stripTrailingSlash(baseUrl), apiKey, models));
        }

        Map<String, GatewayConfig.AliasConfig> aliases = new LinkedHashMap<>();
        String simple = "fast";
        String complex = "smart";
        for (Map.Entry<String, JsonNode> e : root.path("model_aliases").properties()) {
            String alias = e.getKey();
            JsonNode a = e.getValue();
            if (alias.startsWith("_")) {
                continue;
            }
            if (a.has("route_by_difficulty")) {
                simple = a.path("route_by_difficulty").path("simple").asString(simple);
                complex = a.path("route_by_difficulty").path("complex").asString(complex);
                continue;
            }
            List<String> fallbacks = new ArrayList<>();
            a.path("fallbacks").forEach(f -> fallbacks.add(f.asString()));
            aliases.put(alias, new GatewayConfig.AliasConfig(alias, a.path("primary").asString(), List.copyOf(fallbacks)));
        }

        JsonNode retry = root.path("retry");
        GatewayConfig.RetryPolicy retryPolicy = new GatewayConfig.RetryPolicy(
                retry.path("max_attempts").asInt(3),
                retry.path("initial_backoff_ms").asLong(200),
                retry.path("backoff_multiplier").asDouble(2.0));

        return new GatewayConfig(List.copyOf(providers), Map.copyOf(aliases),
                new GatewayConfig.AutoRouteConfig(simple, complex), retryPolicy);
    }

    @Bean
    public PriceTable priceTable(PrismProperties props, ObjectMapper mapper) {
        JsonNode root = readJson(mapper, props.pricingFile());
        Map<String, ModelPrice> prices = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            JsonNode v = e.getValue();
            prices.put(e.getKey(), new ModelPrice(e.getKey(),
                    v.path("input_per_1m").decimalValue(),
                    v.path("output_per_1m").decimalValue()));
        }
        return new PriceTable(prices);
    }

    static JsonNode readJson(ObjectMapper mapper, String file) {
        try {
            return mapper.readTree(Files.readString(Path.of(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + Path.of(file).toAbsolutePath()
                    + " - run the gateway from the repository root or set the prism.* file properties", e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Model name to price; the source of truth for cost accounting. */
    public record PriceTable(Map<String, ModelPrice> prices) {

        public ModelPrice get(String model) {
            return prices.get(model);
        }

        public boolean contains(String model) {
            return prices.containsKey(model);
        }

        public BigDecimal inputPer1m(String model) {
            return prices.get(model).inputPer1m();
        }
    }
}
