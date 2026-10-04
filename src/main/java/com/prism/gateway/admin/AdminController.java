package com.prism.gateway.admin;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.prism.gateway.cache.SemanticCache;
import com.prism.gateway.cache.SparseVector;
import com.prism.gateway.cache.TextEmbedder;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.keys.KeyService;
import com.prism.gateway.keys.VirtualKey;
import com.prism.gateway.limits.BudgetService;
import com.prism.gateway.provider.CircuitBreakers;
import com.prism.gateway.provider.ProviderHealthTracker;
import com.prism.gateway.provider.ProviderRegistry;
import com.prism.gateway.routing.DifficultyRouter;
import com.prism.gateway.routing.RoutingDecision;
import com.prism.gateway.routing.RoutingEvalService;

/** Admin plane (token-protected, see {@link AdminAuthInterceptor}). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminQueries queries;
    private final KeyService keys;
    private final BudgetService budgets;
    private final SemanticCache cache;
    private final TextEmbedder embedder;
    private final DifficultyRouter router;
    private final RoutingEvalService routingEval;
    private final ProviderHealthTracker health;
    private final ProviderRegistry providers;
    private final CircuitBreakers breakers;
    private final PrismProperties props;
    private final Clock clock;

    public AdminController(AdminQueries queries, KeyService keys, BudgetService budgets, SemanticCache cache,
                           TextEmbedder embedder, DifficultyRouter router, RoutingEvalService routingEval, ProviderHealthTracker health,
                           ProviderRegistry providers, CircuitBreakers breakers, PrismProperties props, Clock clock) {
        this.queries = queries;
        this.keys = keys;
        this.budgets = budgets;
        this.cache = cache;
        this.embedder = embedder;
        this.router = router;
        this.routingEval = routingEval;
        this.health = health;
        this.providers = providers;
        this.breakers = breakers;
        this.props = props;
        this.clock = clock;
    }

    /**
     * {@code from}/{@code to} are inclusive UTC dates (default: this calendar month so far).
     * With {@code key}: that key's totals. Without: one row per key, plus month-to-date budget status.
     */
    @GetMapping("/usage")
    public Object usage(@RequestParam(required = false) String key,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate fromDate = from == null ? today.withDayOfMonth(1) : parseDate(from);
        LocalDate toDate = to == null ? today : parseDate(to);
        Instant start = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        if (key != null) {
            VirtualKey vk = keys.find(key).orElseThrow(() -> new IllegalArgumentException("Unknown key"));
            return usageRow(vk, start, end, fromDate, toDate);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VirtualKey vk : keys.all()) {
            rows.add(usageRow(vk, start, end, fromDate, toDate));
        }
        return Map.of("from", fromDate.toString(), "to", toDate.toString(), "keys", rows);
    }

    private Map<String, Object> usageRow(VirtualKey vk, Instant start, Instant end, LocalDate from, LocalDate to) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", vk.key());
        row.put("team", vk.team());
        row.put("from", from.toString());
        row.put("to", to.toString());
        row.putAll(queries.usage(vk.key(), start, end));
        BudgetService.Status budget = budgets.check(vk);
        Map<String, Object> b = new LinkedHashMap<>();
        AdminQueries.putCost(b, "month_to_date_usd", budget.spent());
        AdminQueries.putCost(b, "monthly_budget_usd", budget.budget());
        b.put("exhausted", budget.exhausted());
        row.put("budget", b);
        row.put("requests_per_minute", vk.requestsPerMinute());
        return row;
    }

    @GetMapping("/logs")
    public List<Map<String, Object>> logs(@RequestParam(required = false) String key,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return queries.logs(key, status, Math.max(1, Math.min(limit, 1000)));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return queries.overview(500);
    }

    /** Hit rate = hits / (hits + misses); bypassed requests (cache off, time-sensitive...) are excluded. */
    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        Map<String, Long> entries = cache.entryCounts();
        List<Map<String, Object>> perKey = new ArrayList<>();
        long hits = 0;
        long misses = 0;
        long bypassed = 0;
        Map<String, Map<String, Object>> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : queries.cacheCounts()) {
            counts.put((String) row.get("key"), row);
        }
        for (VirtualKey vk : keys.all()) {
            Map<String, Object> c = counts.getOrDefault(vk.key(), Map.of("hits", 0L, "misses", 0L, "bypassed", 0L));
            long h = (Long) c.get("hits");
            long m = (Long) c.get("misses");
            long b = (Long) c.get("bypassed");
            hits += h;
            misses += m;
            bypassed += b;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", vk.masked());
            row.put("team", vk.team());
            row.put("enabled", vk.cacheEnabled());
            row.put("similarity_threshold", vk.cacheSimilarityThreshold());
            row.put("entries", entries.getOrDefault(vk.key(), 0L));
            row.put("hits", h);
            row.put("misses", m);
            row.put("bypassed", b);
            row.put("hit_rate", rate(h, m));
            perKey.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embedder", cache.embedderName());
        result.put("ttl_minutes", props.cache().ttlMinutes());
        result.put("hits", hits);
        result.put("misses", misses);
        result.put("bypassed", bypassed);
        result.put("hit_rate", rate(hits, misses));
        result.put("keys", perKey);
        return result;
    }

    /** Debug aid: how similar does the cache consider two prompts, and which terms did it compare? */
    @PostMapping("/cache/similarity")
    public Map<String, Object> similarity(@RequestBody Map<String, String> body) {
        SparseVector a = embedder.embed(body.getOrDefault("a", ""));
        SparseVector b = embedder.embed(body.getOrDefault("b", ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("similarity", Math.round(a.cosine(b) * 10_000) / 10_000.0);
        result.put("a_terms", a.weights().keySet());
        result.put("b_terms", b.weights().keySet());
        result.put("embedder", embedder.name());
        return result;
    }

    @DeleteMapping("/cache")
    public Map<String, Object> clearCache(@RequestParam(required = false) String key) {
        return Map.of("removed", cache.clear(key), "key", key == null ? "(all)" : VirtualKey.mask(key));
    }

    /**
     * {@code set=pack} (default) is the graded data/routing_eval.jsonl; any other name selects
     * data/routing_<set>.jsonl next to it (e.g. {@code holdout}, {@code holdout2}).
     */
    @GetMapping("/routing/eval")
    public Map<String, Object> routingEval(@RequestParam(defaultValue = "pack") String set) {
        if (!set.matches("[a-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("set must be a short lowercase name such as 'pack' or 'holdout'");
        }
        Path pack = Path.of(props.routingEvalFile());
        Path file = "pack".equals(set) ? pack : pack.resolveSibling("routing_" + set + ".jsonl");
        if (!java.nio.file.Files.exists(file)) {
            throw new IllegalArgumentException("No eval file " + file.getFileName());
        }
        return routingEval.evaluate(file);
    }

    @PostMapping("/routing/classify")
    public Map<String, Object> classify(@RequestBody Map<String, String> body) {
        RoutingDecision d = router.classify(body.getOrDefault("prompt", ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("difficulty", d.difficulty());
        result.put("score", d.score());
        result.put("signals", d.signals());
        result.put("task_text", d.taskText());
        result.put("reason", d.reason());
        return result;
    }

    /** Rolling 60 s attempt stats, circuit-breaker state and bulkhead occupancy per provider. */
    @GetMapping("/providers/health")
    public Map<String, Map<String, Object>> providerHealth() {
        Map<String, CircuitBreakers.State> circuits = breakers.states(providers.names());
        Map<String, Integer> inFlight = providers.inFlight();
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        health.snapshot(providers.names()).forEach((name, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attempts", s.attempts());
            row.put("errors", s.errors());
            row.put("errorRate", s.errorRate());
            row.put("avgLatencyMs", s.avgLatencyMs());
            row.put("p95LatencyMs", s.p95LatencyMs());
            row.put("lastOutcome", s.lastOutcome());
            row.put("circuit", circuits.get(name).name().toLowerCase(java.util.Locale.ROOT));
            row.put("healthy", s.healthy() && circuits.get(name) == CircuitBreakers.State.CLOSED);
            row.put("inFlight", inFlight.get(name));
            result.put(name, row);
        });
        return result;
    }

    @GetMapping("/keys")
    public List<Map<String, Object>> keyList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (VirtualKey vk : keys.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", vk.masked());
            row.put("team", vk.team());
            row.put("active", vk.active());
            row.put("model_allowlist", vk.modelAllowlist());
            row.put("requests_per_minute", vk.requestsPerMinute());
            row.put("monthly_budget_usd", vk.monthlyBudgetUsd());
            row.put("cache_enabled", vk.cacheEnabled());
            row.put("cache_similarity_threshold", vk.cacheSimilarityThreshold());
            BigDecimal spent = budgets.check(vk).spent();
            AdminQueries.putCost(row, "month_to_date_usd", spent);
            result.add(row);
        }
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", Map.of("message", e.getMessage(), "type", "invalid_request_error")));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must be ISO yyyy-MM-dd, got '" + value + "'");
        }
    }

    private static double rate(long hits, long misses) {
        long total = hits + misses;
        return total == 0 ? 0.0 : Math.round(1000.0 * hits / total) / 1000.0;
    }
}
