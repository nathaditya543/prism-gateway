package com.prism.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.prism.gateway.keys.VirtualKey;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decides whether a request may use the cache and what it is matched on.
 *
 * <p>Cache key design for multi-turn requests: only the <em>last user message</em> is compared
 * semantically; everything before it (system prompt, earlier turns) plus output-shaping
 * parameters must match <em>exactly</em> via a context hash in the scope. So a paraphrased
 * follow-up in the same conversation can hit, but the same question in a different conversation
 * (where "it" refers to something else) cannot.
 */
@Component
public class CachePolicy {

    /** Prompts whose correct answer changes over time. Serving them from cache would be wrong. */
    static final Pattern TIME_SENSITIVE = Pattern.compile(
            "\\b(current(ly)?|right now|now|today|tonight|tomorrow|yesterday|latest|at the moment|"
                    + "this (week|month|year|morning|afternoon|evening)|live|real[- ]?time|status of|"
                    + "up[- ]to[- ]date|recent(ly)?|news|price of|weather|time is it)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Request fields that change the completion; part of the exact-match context. */
    private static final List<String> OUTPUT_PARAMS = List.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop", "n", "seed",
            "presence_penalty", "frequency_penalty", "response_format", "tools", "tool_choice", "logit_bias");

    private final TextEmbedder embedder;
    private final ObjectMapper mapper;

    public CachePolicy(TextEmbedder embedder, ObjectMapper mapper) {
        this.embedder = embedder;
        this.mapper = mapper;
    }

    /**
     * @param tierModel the alias/tier the request will be served by (scope component)
     * @param cacheControl the client's {@code Cache-Control} header: {@code no-cache} skips lookup
     *                     but still stores, {@code no-store} skips both
     */
    public Decision decide(VirtualKey key, ObjectNode body, String tierModel, String cacheControl) {
        if (!key.cacheEnabled()) {
            return Decision.bypass("cache disabled for this key");
        }
        String cc = cacheControl == null ? "" : cacheControl.toLowerCase();
        if (cc.contains("no-store")) {
            return Decision.bypass("client sent Cache-Control: no-store");
        }
        if (body.path("n").asInt(1) > 1) {
            return Decision.bypass("n > 1");
        }
        ArrayNode messages = (ArrayNode) body.path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        if (!"user".equals(last.path("role").asString()) || !last.path("content").isString()) {
            return Decision.bypass("last message is not plain user text");
        }
        String query = last.path("content").asString();
        if (TIME_SENSITIVE.matcher(query).find()) {
            return Decision.bypass("time-sensitive prompt");
        }
        SparseVector vector = embedder.embed(query);
        if (vector.isEmpty()) {
            return Decision.bypass("prompt has no content words");
        }
        String scope = key.key() + "|" + tierModel + "|" + contextHash(body, messages);
        boolean lookup = !cc.contains("no-cache");
        return new Decision(true, lookup, null, scope, query, vector);
    }

    private String contextHash(ObjectNode body, ArrayNode messages) {
        ObjectNode context = mapper.createObjectNode();
        ArrayNode prior = context.putArray("messages");
        for (int i = 0; i < messages.size() - 1; i++) {
            JsonNode m = messages.get(i);
            ObjectNode copy = prior.addObject();
            copy.put("role", m.path("role").asString());
            copy.set("content", m.path("content"));
        }
        for (String param : OUTPUT_PARAMS) {
            if (body.has(param)) {
                context.set(param, body.get(param));
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(context).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param use    the cache participates at all (store on success)
     * @param lookup also look for an existing entry first
     */
    public record Decision(boolean use, boolean lookup, String bypassReason, String scope, String queryText,
                           SparseVector vector) {

        static Decision bypass(String reason) {
            return new Decision(false, false, reason, null, null, null);
        }
    }
}
