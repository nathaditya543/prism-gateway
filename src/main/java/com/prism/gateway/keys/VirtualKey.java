package com.prism.gateway.keys;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable snapshot of a tenant's key and limits, used on the hot path. */
public record VirtualKey(
        String key,
        String team,
        BigDecimal monthlyBudgetUsd,
        int requestsPerMinute,
        Integer tokensPerMinute,
        Set<String> modelAllowlist,
        boolean cacheEnabled,
        Double cacheSimilarityThreshold,
        boolean active,
        Instant createdAt) {

    public boolean allows(String requestedModel) {
        return modelAllowlist.contains(requestedModel);
    }

    /** Safe for logs and the console: never the full secret. */
    public String masked() {
        return mask(key);
    }

    public static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "(none)";
        }
        int keep = Math.min(12, Math.max(0, key.length() - 4));
        return key.substring(0, keep) + "…";
    }

    static Set<String> parseAllowlist(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String toString() {
        return "VirtualKey[" + masked() + ", team=" + team + "]";
    }
}
