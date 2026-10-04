package com.prism.gateway.cache;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.prism.gateway.config.PrismProperties;

/**
 * Per-scope nearest-neighbour cache over prompt vectors.
 *
 * <p>Storage: every entry is persisted in {@code cache_entries} and mirrored in an in-memory index
 * (rebuilt on startup) so lookups never touch the database. Lookup is a linear scan within one
 * scope, which is small because scopes are per key + tier + conversation context.
 *
 * <p>Eviction: entries expire after the configured TTL (expired entries are skipped and purged on
 * startup), and a scope keeps at most {@code maxEntriesPerScope} entries, evicting the oldest.
 */
@Service
@Order(2)
public class SemanticCache implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    /** Two prompts this similar are the same prompt; storing both would only waste space. */
    private static final double DUPLICATE_SIMILARITY = 0.999;

    private final CacheEntryRepository repository;
    private final JdbcTemplate jdbc;
    private final TextEmbedder embedder;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntriesPerScope;
    private final Map<String, List<Entry>> index = new ConcurrentHashMap<>();

    public SemanticCache(CacheEntryRepository repository, JdbcTemplate jdbc, TextEmbedder embedder, Clock clock,
                         PrismProperties props) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(props.cache().ttlMinutes());
        this.maxEntriesPerScope = props.cache().maxEntriesPerScope();
    }

    @Override
    public void run(ApplicationArguments args) {
        int purged = jdbc.update("DELETE FROM cache_entries WHERE expires_at <= ? OR embedder <> ?",
                clock.instant(), embedder.name());
        int loaded = 0;
        for (CacheEntryEntity e : repository.findAll()) {
            index.computeIfAbsent(e.getScope(), s -> new CopyOnWriteArrayList<>()).add(Entry.from(e));
            loaded++;
        }
        log.info("Semantic cache loaded {} entries ({} expired/stale purged), embedder={}", loaded, purged,
                embedder.name());
    }

    public Optional<Hit> lookup(String scope, SparseVector vector, double threshold) {
        List<Entry> entries = index.get(scope);
        if (entries == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Entry best = null;
        double bestScore = -1;
        for (Entry e : entries) {
            if (e.expiresAt().isBefore(now)) {
                continue;
            }
            double score = e.vector().cosine(vector);
            if (score > bestScore) {
                best = e;
                bestScore = score;
            }
        }
        if (best == null || bestScore < threshold) {
            return Optional.empty();
        }
        jdbc.update("UPDATE cache_entries SET hit_count = hit_count + 1, last_hit_at = ? WHERE id = ?", now, best.id());
        return Optional.of(new Hit(best, bestScore));
    }

    public void store(String scope, String virtualKey, String model, String promptText, SparseVector vector,
                      String responseJson, String servedBy, BigDecimal originalCost) {
        List<Entry> entries = index.computeIfAbsent(scope, s -> new CopyOnWriteArrayList<>());
        if (entries.stream().anyMatch(e -> e.vector().cosine(vector) >= DUPLICATE_SIMILARITY)) {
            return;
        }
        Instant now = clock.instant();
        CacheEntryEntity entity = new CacheEntryEntity(UUID.randomUUID().toString(), virtualKey, model, scope,
                embedder.name(), promptText, vector.serialize(), responseJson, servedBy, originalCost,
                now, now.plus(ttl));
        repository.save(entity);
        entries.add(Entry.from(entity));
        while (entries.size() > maxEntriesPerScope) {
            Entry oldest = entries.stream().min(Comparator.comparing(Entry::createdAt)).orElseThrow();
            entries.remove(oldest);
            repository.deleteById(oldest.id());
        }
    }

    /** Drops every entry for a key (or all keys when null). Returns the number removed. */
    public int clear(String virtualKey) {
        int removed;
        if (virtualKey == null) {
            removed = jdbc.update("DELETE FROM cache_entries");
            index.clear();
        } else {
            removed = jdbc.update("DELETE FROM cache_entries WHERE virtual_key = ?", virtualKey);
            index.keySet().removeIf(scope -> scope.startsWith(virtualKey + "|"));
        }
        return removed;
    }

    /** Live (unexpired) entries per key. */
    public Map<String, Long> entryCounts() {
        Instant now = clock.instant();
        Map<String, Long> counts = new ConcurrentHashMap<>();
        index.forEach((scope, entries) -> {
            String key = scope.substring(0, scope.indexOf('|'));
            long live = entries.stream().filter(e -> e.expiresAt().isAfter(now)).count();
            counts.merge(key, live, Long::sum);
        });
        return counts;
    }

    public String embedderName() {
        return embedder.name();
    }

    public record Entry(String id, SparseVector vector, String responseJson, String servedBy, String promptText,
                        Instant createdAt, Instant expiresAt) {

        static Entry from(CacheEntryEntity e) {
            return new Entry(e.getId(), SparseVector.parse(e.getVector()), e.getResponseJson(), e.getServedBy(),
                    e.getPromptText(), e.getCreatedAt(), e.getExpiresAt());
        }
    }

    public record Hit(Entry entry, double similarity) {
    }
}
