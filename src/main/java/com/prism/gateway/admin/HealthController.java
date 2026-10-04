package com.prism.gateway.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prism.gateway.keys.KeyService;
import com.prism.gateway.provider.ProviderRegistry;

/** Public liveness check: the database answers and keys are loaded. */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final KeyService keys;
    private final ProviderRegistry providers;

    public HealthController(JdbcTemplate jdbc, KeyService keys, ProviderRegistry providers) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.providers = providers;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            db = false;
        }
        List<String> names = new ArrayList<>();
        providers.names().forEach(names::add);
        // The HTTP server comes up a moment before the seed keys are loaded; don't claim ready until they are.
        int keyCount = keys.all().size();
        result.put("status", !db ? "degraded" : keyCount == 0 ? "starting" : "ok");
        result.put("database", db ? "ok" : "unreachable");
        result.put("virtual_keys", keyCount);
        result.put("providers", names);
        return result;
    }
}
