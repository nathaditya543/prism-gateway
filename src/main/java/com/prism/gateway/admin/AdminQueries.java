package com.prism.gateway.admin;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.prism.gateway.keys.VirtualKey;
import com.prism.gateway.usage.CostCalculator;

/** Read-side aggregates over request logs for the admin API and console. */
@Repository
public class AdminQueries {

    private final JdbcTemplate jdbc;

    public AdminQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Usage for one key over [from, to). {@code requests} counts served requests (HTTP 200: upstream
     * or cache), which is what the load test counts as accepted; rejections are reported separately.
     */
    public Map<String, Object> usage(String key, Instant from, Instant to) {
        return jdbc.queryForObject("""
                SELECT COUNT(CASE WHEN status IN ('ok', 'cache_hit') THEN 1 END)                 AS served,
                       COUNT(CASE WHEN status LIKE 'rejected%' THEN 1 END)                       AS rejected,
                       COUNT(CASE WHEN status NOT IN ('ok', 'cache_hit') AND status NOT LIKE 'rejected%' THEN 1 END) AS failed,
                       COALESCE(SUM(prompt_tokens), 0)                                            AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0)                                        AS completion_tokens,
                       COALESCE(SUM(cost_usd), 0)                                                 AS cost_usd,
                       COUNT(CASE WHEN cache = 'hit' THEN 1 END)                                  AS cache_hits
                  FROM request_logs
                 WHERE virtual_key = ? AND created_at >= ? AND created_at < ?""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requests", rs.getLong("served"));
                    m.put("rejected", rs.getLong("rejected"));
                    m.put("failed", rs.getLong("failed"));
                    m.put("prompt_tokens", rs.getLong("prompt_tokens"));
                    m.put("completion_tokens", rs.getLong("completion_tokens"));
                    putCost(m, "cost_usd", rs.getBigDecimal("cost_usd"));
                    m.put("cache_hits", rs.getLong("cache_hits"));
                    return m;
                }, key, ts(from), ts(to));
    }

    public List<Map<String, Object>> logs(String key, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM request_logs WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (key != null && !key.isBlank()) {
            sql.append(" AND virtual_key = ?");
            args.add(key);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> logRow(rs), args.toArray());
    }

    /** Hits / misses / bypasses per key, from the request log (survives restarts). */
    public List<Map<String, Object>> cacheCounts() {
        return jdbc.query("""
                SELECT virtual_key,
                       COUNT(CASE WHEN cache = 'hit' THEN 1 END)    AS hits,
                       COUNT(CASE WHEN cache = 'miss' THEN 1 END)   AS misses,
                       COUNT(CASE WHEN cache = 'bypass' THEN 1 END) AS bypassed
                  FROM request_logs
                 WHERE virtual_key IS NOT NULL AND cache IN ('hit', 'miss', 'bypass')
                 GROUP BY virtual_key""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", rs.getString("virtual_key"));
                    m.put("hits", rs.getLong("hits"));
                    m.put("misses", rs.getLong("misses"));
                    m.put("bypassed", rs.getLong("bypassed"));
                    return m;
                });
    }

    /** Status counts and latency samples over the most recent requests, for the console overview. */
    public Map<String, Object> overview(int window) {
        List<Map<String, Object>> statusCounts = jdbc.query("""
                SELECT status, COUNT(*) AS n FROM request_logs GROUP BY status ORDER BY n DESC""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("count", rs.getLong("n"));
                    return m;
                });
        List<long[]> samples = jdbc.query("""
                SELECT latency_ms, upstream_latency_ms FROM request_logs
                 WHERE status = 'ok' AND stream = FALSE AND upstream_latency_ms IS NOT NULL
                 ORDER BY created_at DESC LIMIT ?""",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, window);
        List<Long> total = samples.stream().map(s -> s[0]).sorted().toList();
        List<Long> added = samples.stream().map(s -> Math.max(0, s[0] - s[1])).sorted().toList();

        Map<String, Object> totals = jdbc.queryForObject("""
                SELECT COUNT(*) AS requests,
                       COALESCE(SUM(cost_usd), 0) AS cost,
                       COUNT(CASE WHEN fallback THEN 1 END) AS fallbacks,
                       COALESCE(SUM(retries), 0) AS retries
                  FROM request_logs""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requests", rs.getLong("requests"));
                    putCost(m, "cost_usd", rs.getBigDecimal("cost"));
                    m.put("fallbacks", rs.getLong("fallbacks"));
                    m.put("retries", rs.getLong("retries"));
                    return m;
                });
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("sample_size", samples.size());
        latency.put("total_ms", percentiles(total));
        latency.put("gateway_added_ms", percentiles(added));
        latency.put("method", "per non-streaming request: total latency minus time spent in upstream calls "
                + "(includes auth, limits, cache lookup, logging, and retry backoff)");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totals", totals);
        result.put("by_status", statusCounts);
        result.put("latency", latency);
        return result;
    }

    private static Map<String, Object> percentiles(List<Long> sorted) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (sorted.isEmpty()) {
            return m;
        }
        m.put("avg", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
        m.put("p50", sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1));
        m.put("p95", sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1));
        m.put("max", sorted.get(sorted.size() - 1));
        return m;
    }

    private static Map<String, Object> logRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request_id", rs.getString("request_id"));
        m.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        m.put("virtual_key", VirtualKey.mask(rs.getString("virtual_key")));
        m.put("key_hint", rs.getString("key_hint"));
        m.put("team", rs.getString("team"));
        m.put("requested_model", rs.getString("requested_model"));
        m.put("resolved_provider", rs.getString("resolved_provider"));
        m.put("resolved_model", rs.getString("resolved_model"));
        m.put("status", rs.getString("status"));
        m.put("http_status", rs.getInt("http_status"));
        m.put("stream", rs.getBoolean("stream"));
        m.put("prompt_tokens", rs.getLong("prompt_tokens"));
        m.put("completion_tokens", rs.getLong("completion_tokens"));
        putCost(m, "cost_usd", rs.getBigDecimal("cost_usd"));
        m.put("cache", rs.getString("cache"));
        m.put("cache_similarity", rs.getObject("cache_similarity"));
        m.put("fallback", rs.getBoolean("fallback"));
        m.put("route_tier", rs.getString("route_tier"));
        m.put("route_reason", rs.getString("route_reason"));
        m.put("retries", rs.getInt("retries"));
        m.put("attempts", rs.getString("attempts"));
        m.put("latency_ms", rs.getLong("latency_ms"));
        m.put("upstream_latency_ms", rs.getObject("upstream_latency_ms"));
        m.put("error_type", rs.getString("error_type"));
        m.put("error_message", rs.getString("error_message"));
        return m;
    }

    /** Exact decimal as a plain string, plus a JSON number for convenience. */
    static void putCost(Map<String, Object> m, String field, BigDecimal cost) {
        BigDecimal c = cost == null ? BigDecimal.ZERO : cost;
        m.put(field, c.doubleValue());
        m.put(field + "_exact", CostCalculator.format(c));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
