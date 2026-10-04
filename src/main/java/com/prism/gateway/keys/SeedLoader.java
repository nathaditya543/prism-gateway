package com.prism.gateway.keys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.prism.gateway.config.PrismProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Upserts tenants from the seed file on every start. The seed file owns key configuration
 * (budget, limits, allowlist, cache settings); usage and logs are never touched.
 */
@Component
@Order(1)
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final PrismProperties props;
    private final ObjectMapper mapper;
    private final VirtualKeyRepository repository;
    private final KeyService keyService;
    private final Clock clock;

    public SeedLoader(PrismProperties props, ObjectMapper mapper, VirtualKeyRepository repository,
                      KeyService keyService, Clock clock) {
        this.props = props;
        this.mapper = mapper;
        this.repository = repository;
        this.keyService = keyService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(Path.of(props.seedKeysFile())));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read seed keys file " + props.seedKeysFile(), e);
        }
        int count = 0;
        for (JsonNode t : root.path("tenants")) {
            String key = t.path("virtual_key").asString();
            VirtualKeyEntity entity = repository.findById(key)
                    .orElseGet(() -> new VirtualKeyEntity(key, clock.instant()));
            entity.setTeam(t.path("team").asString());
            entity.setMonthlyBudgetUsd(new BigDecimal(t.path("monthly_budget_usd").asString()));
            entity.setRequestsPerMinute(t.path("rate_limit").path("requests_per_minute").asInt());
            JsonNode tpm = t.path("rate_limit").path("tokens_per_minute");
            entity.setTokensPerMinute(tpm.isNumber() ? tpm.asInt() : null);
            List<String> allow = new ArrayList<>();
            t.path("model_allowlist").forEach(m -> allow.add(m.asString()));
            entity.setModelAllowlist(String.join(",", allow));
            JsonNode cache = t.path("semantic_cache");
            entity.setCacheEnabled(cache.path("enabled").asBoolean(false));
            JsonNode threshold = cache.path("similarity_threshold");
            entity.setCacheSimilarityThreshold(threshold.isNumber() ? threshold.asDouble() : null);
            entity.setStatus(t.path("status").asString("active"));
            repository.save(entity);
            count++;
        }
        keyService.reload();
        log.info("Seeded {} virtual keys from {}", count, props.seedKeysFile());
    }
}
