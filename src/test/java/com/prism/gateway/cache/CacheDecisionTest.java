package com.prism.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.prism.gateway.keys.VirtualKey;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The cache decision: what counts as "the same question", and when the cache must stay out of it. */
class CacheDecisionTest {

    private final LexicalEmbedder embedder = new LexicalEmbedder();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CachePolicy policy = new CachePolicy(embedder, mapper);

    private static final double SEARCH_THRESHOLD = 0.92;

    private double similarity(String a, String b) {
        return embedder.embed(a).cosine(embedder.embed(b));
    }

    @Test
    void paraphrasePairAMatchesAboveTheStrictestSeedThreshold() {
        assertThat(similarity("How do I reset my password on the dashboard?",
                "What are the steps to reset my dashboard password?")).isGreaterThanOrEqualTo(SEARCH_THRESHOLD);
    }

    @Test
    void nearMissWithDifferentIntentDoesNotMatch() {
        // req_cache_a3: same words "reset" and "dashboard", different subject (2FA, not password)
        assertThat(similarity("How do I reset my password on the dashboard?",
                "How do I reset my two-factor authentication on the dashboard?")).isLessThan(0.85);
    }

    @Test
    void differentNumbersAreDifferentQuestions() {
        assertThat(similarity("Convert 72 degrees Fahrenheit to Celsius.",
                "Convert 73 degrees Fahrenheit to Celsius.")).isLessThan(0.85);
    }

    @Test
    void negationIsKept() {
        assertThat(similarity("Why does the build fail?", "Why doesn't the build fail?")).isLessThan(SEARCH_THRESHOLD);
    }

    @Test
    void identifiersSeparateOtherwiseIdenticalPrompts() {
        // The smoke test salts prompts with a random id; an old run's entry must not be served.
        assertThat(similarity("(1a2b3c4d) What is a load balancer?", "(9f8e7d6c) What is a load balancer?"))
                .isLessThan(0.85);
    }

    @Test
    void scopeIsolatesTenants() {
        ObjectNode body = request("How do I reset my password on the dashboard?");
        CachePolicy.Decision a = policy.decide(key("prism-sk-a", true), body, "fast", null);
        CachePolicy.Decision b = policy.decide(key("prism-sk-b", true), body, "fast", null);
        assertThat(a.scope()).isNotEqualTo(b.scope());
        assertThat(a.scope()).startsWith("prism-sk-a|fast|");
    }

    @Test
    void scopeSeparatesTiersAndConversationContext() {
        VirtualKey k = key("prism-sk-a", true);
        ObjectNode single = request("What happens when the pool is exhausted?");
        ObjectNode multi = mapper.createObjectNode().put("model", "fast");
        var messages = multi.putArray("messages");
        messages.addObject().put("role", "user").put("content", "What is connection pooling?");
        messages.addObject().put("role", "assistant").put("content", "It reuses connections.");
        messages.addObject().put("role", "user").put("content", "What happens when the pool is exhausted?");

        assertThat(policy.decide(k, single, "fast", null).scope())
                .isNotEqualTo(policy.decide(k, multi, "fast", null).scope())
                .isNotEqualTo(policy.decide(k, single, "smart", null).scope());
    }

    @Test
    void timeSensitivePromptsBypassTheCache() {
        CachePolicy.Decision d = policy.decide(key("k", true),
                request("What is the current status of the payments service?"), "fast", null);
        assertThat(d.use()).isFalse();
        assertThat(d.bypassReason()).contains("time-sensitive");
    }

    @Test
    void disabledKeyBypasses() {
        assertThat(policy.decide(key("k", false), request("What is a queue?"), "fast", null).use()).isFalse();
    }

    @Test
    void clientCacheControlIsHonoured() {
        VirtualKey k = key("k", true);
        CachePolicy.Decision noCache = policy.decide(k, request("What is a queue?"), "fast", "no-cache");
        assertThat(noCache.use()).isTrue();
        assertThat(noCache.lookup()).isFalse();
        assertThat(policy.decide(k, request("What is a queue?"), "fast", "no-store").use()).isFalse();
    }

    private ObjectNode request(String prompt) {
        ObjectNode body = mapper.createObjectNode().put("model", "fast");
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        return body;
    }

    private static VirtualKey key(String key, boolean cache) {
        return new VirtualKey(key, "team", BigDecimal.TEN, 60, null, Set.of("fast"), cache, 0.92, true, Instant.EPOCH);
    }
}
