package com.prism.gateway.gateway;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.prism.gateway.cache.CachePolicy;
import com.prism.gateway.cache.SemanticCache;
import com.prism.gateway.catalog.ModelCatalog;
import com.prism.gateway.catalog.ModelTarget;
import com.prism.gateway.gateway.GatewayReply.SseWriter;
import com.prism.gateway.keys.KeyService;
import com.prism.gateway.keys.VirtualKey;
import com.prism.gateway.limits.BudgetService;
import com.prism.gateway.limits.SlidingWindowRateLimiter;
import com.prism.gateway.provider.ProviderAdapter;
import com.prism.gateway.provider.UpstreamInvoker;
import com.prism.gateway.provider.UpstreamInvoker.Invocation;
import com.prism.gateway.provider.UpstreamInvoker.UpstreamFailure;
import com.prism.gateway.provider.UpstreamStream;
import com.prism.gateway.routing.DifficultyRouter;
import com.prism.gateway.routing.RoutingDecision;
import com.prism.gateway.usage.CostCalculator;
import com.prism.gateway.usage.RequestLogEntity;
import com.prism.gateway.usage.RequestStatus;
import com.prism.gateway.usage.UsageRecorder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The data-plane pipeline for {@code POST /v1/chat/completions}. Checks run in this order, each
 * cheaper or more fundamental than the next:
 * <ol>
 *   <li>authenticate the virtual key (401)</li>
 *   <li>validate the body (400)</li>
 *   <li>resolve the model / alias (404)</li>
 *   <li>model allowlist (403)</li>
 *   <li>rate limit - in-memory, consumes a slot (429)</li>
 *   <li>{@code auto}: classify difficulty and pick a tier</li>
 *   <li>monthly budget - reserve the estimated cost against spend + in-flight reservations (402)</li>
 *   <li>semantic cache lookup</li>
 *   <li>upstream call: retries, failover, timeouts</li>
 *   <li>meter cost from provider usage, write the request log + usage, store in cache</li>
 * </ol>
 * Every request, including every rejection, produces exactly one request log row.
 */
@Service
public class ChatGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ChatGatewayService.class);
    private static final double DEFAULT_CACHE_THRESHOLD = 0.9;
    /** Completion length assumed for budget reservation when the caller sets no max_tokens. */
    private static final long DEFAULT_COMPLETION_ESTIMATE = 256;

    private final ObjectMapper mapper;
    private final KeyService keys;
    private final ModelCatalog catalog;
    private final SlidingWindowRateLimiter rateLimiter;
    private final BudgetService budgets;
    private final DifficultyRouter router;
    private final CachePolicy cachePolicy;
    private final SemanticCache cache;
    private final UpstreamInvoker invoker;
    private final CostCalculator costs;
    private final UsageRecorder recorder;
    private final Clock clock;

    public ChatGatewayService(ObjectMapper mapper, KeyService keys, ModelCatalog catalog,
                              SlidingWindowRateLimiter rateLimiter, BudgetService budgets, DifficultyRouter router,
                              CachePolicy cachePolicy, SemanticCache cache, UpstreamInvoker invoker,
                              CostCalculator costs, UsageRecorder recorder, Clock clock) {
        this.mapper = mapper;
        this.keys = keys;
        this.catalog = catalog;
        this.rateLimiter = rateLimiter;
        this.budgets = budgets;
        this.router = router;
        this.cachePolicy = cachePolicy;
        this.cache = cache;
        this.invoker = invoker;
        this.costs = costs;
        this.recorder = recorder;
        this.clock = clock;
    }

    public GatewayReply handle(String authorization, String rawBody, String cacheControl) {
        Exchange ex = new Exchange("req_" + UUID.randomUUID().toString().replace("-", ""), System.nanoTime());
        ex.entry = new RequestLogEntity(ex.requestId, clock.instant());
        ex.entry.setCache("n/a");
        ex.headers.put("x-prism-request-id", ex.requestId);
        try {
            VirtualKey key = authenticate(authorization, ex);
            ChatRequest request = ChatRequest.parse(rawBody, mapper);
            ex.entry.setRequestedModel(request.model());
            ex.entry.setStream(request.stream());
            admit(key, request, ex);

            String tier = chooseTier(request, ex);
            List<ModelTarget> targets = catalog.targetsFor(tier);
            reserveBudget(key, request, targets, ex);

            CachePolicy.Decision cacheDecision = cachePolicy.decide(key, request.body(), tier, cacheControl);
            ex.entry.setCache(cacheDecision.use() ? "miss" : "bypass");
            if (!cacheDecision.use()) {
                ex.headers.put("x-prism-cache-bypass", cacheDecision.bypassReason());
            }
            if (cacheDecision.lookup()) {
                double threshold = key.cacheSimilarityThreshold() != null
                        ? key.cacheSimilarityThreshold() : DEFAULT_CACHE_THRESHOLD;
                Optional<SemanticCache.Hit> hit = cache.lookup(cacheDecision.scope(), cacheDecision.vector(), threshold);
                if (hit.isPresent()) {
                    return cachedReply(hit.get(), request, ex);
                }
            }
            return request.stream()
                    ? streamReply(request, targets, cacheDecision, key, ex)
                    : jsonReply(request, targets, cacheDecision, key, ex);
        } catch (GatewayException e) {
            return reject(e, ex);
        }
    }

    // ---------------------------------------------------------------- admission

    private VirtualKey authenticate(String authorization, Exchange ex) {
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : null;
        Optional<VirtualKey> key = keys.find(token);
        if (key.isEmpty() || !key.get().active()) {
            ex.entry.setKeyHint(VirtualKey.mask(token));
            String message = token == null
                    ? "Missing virtual key: send 'Authorization: Bearer <virtual-key>'"
                    : key.isEmpty() ? "Invalid virtual key" : "Virtual key is disabled";
            throw new GatewayException(401, "authentication_error", RequestStatus.REJECTED_AUTH, message);
        }
        ex.entry.setVirtualKey(key.get().key());
        ex.entry.setKeyHint(key.get().masked());
        ex.entry.setTeam(key.get().team());
        return key.get();
    }

    private void admit(VirtualKey key, ChatRequest request, Exchange ex) {
        String model = request.model();
        if (!catalog.isKnown(model)) {
            throw new GatewayException(404, "not_found_error", RequestStatus.REJECTED_UNKNOWN_MODEL,
                    "The model '" + model + "' does not exist. Use an alias such as 'fast', 'smart' or 'auto'.");
        }
        if (!key.allows(model)) {
            throw new GatewayException(403, "model_not_allowed", RequestStatus.REJECTED_ALLOWLIST,
                    "Model '" + model + "' is not on this key's allowlist " + key.modelAllowlist());
        }
        SlidingWindowRateLimiter.Decision rate = rateLimiter.tryAcquire(key.key(), key.requestsPerMinute());
        ex.headers.put("x-ratelimit-limit-requests", String.valueOf(key.requestsPerMinute()));
        ex.headers.put("x-ratelimit-remaining-requests", String.valueOf(rate.remaining()));
        if (!rate.allowed()) {
            throw new GatewayException(429, "rate_limit_exceeded", RequestStatus.REJECTED_RATE_LIMIT,
                    "Rate limit of " + key.requestsPerMinute() + " requests per minute exceeded for this key",
                    Map.of("Retry-After", String.valueOf(rate.retryAfterSeconds())));
        }
    }

    /** Reserves the request's estimated cost against the budget (see {@link BudgetService}). */
    private void reserveBudget(VirtualKey key, ChatRequest request, List<ModelTarget> targets, Exchange ex) {
        BudgetService.Admission admission = budgets.admit(key, estimateCost(request, targets));
        if (!admission.admitted()) {
            BudgetService.Status budget = admission.status();
            String inFlight = budget.reserved().signum() > 0
                    ? ", $" + CostCalculator.format(budget.reserved()) + " reserved by requests in flight" : "";
            throw new GatewayException(402, "budget_exceeded", RequestStatus.REJECTED_BUDGET,
                    "Monthly budget of $" + budget.budget().stripTrailingZeros().toPlainString()
                            + " exhausted for this key (spent $" + CostCalculator.format(budget.spent()) + inFlight + ")");
        }
        ex.reservation = admission.reservation();
    }

    /**
     * Upper-bound guess at a request's cost, for budget reservation only (never billed): prompt tokens
     * approximated as max(words, chars / 4), completion as the caller's max_tokens or a default, priced at the
     * most expensive model in the chain.
     */
    private BigDecimal estimateCost(ChatRequest request, List<ModelTarget> targets) {
        long chars = 0;
        long words = 0;
        for (JsonNode m : request.body().path("messages")) {
            String content = m.path("content").isString() ? m.path("content").asString() : m.path("content").toString();
            chars += content.length();
            words += Tokens.words(content);
        }
        long prompt = Math.max(words, chars / 4);
        JsonNode maxTokens = request.body().has("max_completion_tokens")
                ? request.body().path("max_completion_tokens") : request.body().path("max_tokens");
        long completion = maxTokens.isNumber() && maxTokens.asLong() > 0 ? maxTokens.asLong() : DEFAULT_COMPLETION_ESTIMATE;
        BigDecimal max = BigDecimal.ZERO;
        for (ModelTarget t : targets) {
            max = max.max(costs.cost(t.model(), prompt, completion));
        }
        return max;
    }

    /** For {@code auto}, classify the prompt; otherwise the caller's alias or model is the tier. */
    private String chooseTier(ChatRequest request, Exchange ex) {
        if (!catalog.isAuto(request.model())) {
            return request.model();
        }
        RoutingDecision decision = router.classify(request.lastUserText());
        String tier = decision.complex() ? catalog.complexTierAlias() : catalog.simpleTierAlias();
        ex.entry.setRouteTier(tier);
        ex.entry.setRouteReason(decision.reason());
        ex.headers.put("x-prism-route", tier + "; score=" + Math.round(decision.score() * 10) / 10.0);
        return tier;
    }

    // ---------------------------------------------------------------- serving

    private GatewayReply jsonReply(ChatRequest request, List<ModelTarget> targets, CachePolicy.Decision cacheDecision,
                                   VirtualKey key, Exchange ex) {
        Invocation<JsonNode> inv = invoke(targets, request, ex, ProviderAdapter::complete);
        JsonNode response = inv.result();
        Tokens tokens = Tokens.from(response.path("usage"));
        if (tokens == null) {
            tokens = Tokens.estimate(request, response.path("choices").path(0).path("message").path("content").asString(""));
            ex.entry.setErrorMessage("provider returned no usage; tokens estimated from text");
        }
        BigDecimal cost = costs.cost(inv.target().model(), tokens.prompt(), tokens.completion());
        fillServed(ex, inv, tokens, cost);
        record(ex);

        if (cacheDecision.use() && isCacheable(response)) {
            cache.store(cacheDecision.scope(), key.key(), ex.tier(), cacheDecision.queryText(), cacheDecision.vector(),
                    mapper.writeValueAsString(response), inv.target().label(), cost);
        }
        Map<String, String> headers = servedHeaders(ex, inv);
        headers.put("x-prism-cost-usd", CostCalculator.format(cost));
        return new GatewayReply.Json(200, headers, mapper.writeValueAsString(response));
    }

    private GatewayReply streamReply(ChatRequest request, List<ModelTarget> targets, CachePolicy.Decision cacheDecision,
                                     VirtualKey key, Exchange ex) {
        // Failover happens here, before the first byte reaches the client, so outputs are never spliced.
        Invocation<UpstreamStream> inv = invoke(targets, request, ex, ProviderAdapter::openStream);
        Map<String, String> headers = servedHeaders(ex, inv);
        return new GatewayReply.Stream(headers, writer -> relay(inv, request, cacheDecision, key, ex, writer));
    }

    /**
     * Forwards upstream chunks as they arrive. Mid-stream failure policy: if the upstream breaks
     * after we started streaming, the client receives an {@code error} event and the stream ends
     * without {@code [DONE]} - we never restart on another provider and splice outputs.
     */
    private void relay(Invocation<UpstreamStream> inv, ChatRequest request, CachePolicy.Decision cacheDecision,
                       VirtualKey key, Exchange ex, SseWriter writer) {
        StringBuilder content = new StringBuilder();
        String finishReason = null;
        String servedModel = inv.target().model();
        JsonNode usage = null;
        boolean done = false;
        boolean clientGone = false;
        String failure = null;

        try (UpstreamStream stream = inv.result()) {
            String data;
            while ((data = stream.nextData()) != null) {
                if ("[DONE]".equals(data)) {
                    done = true;
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (JacksonException e) {
                    failure = "upstream sent a malformed chunk";
                    break;
                }
                if (chunk.has("error")) {
                    failure = "upstream error event: " + chunk.path("error").path("message").asString("unknown");
                    break;
                }
                JsonNode choice = chunk.path("choices").path(0);
                content.append(choice.path("delta").path("content").asString(""));
                if (choice.path("finish_reason").isString()) {
                    finishReason = choice.path("finish_reason").asString();
                }
                if (chunk.path("usage").isObject()) {
                    usage = chunk.path("usage");
                }
                try {
                    writer.data(data);
                } catch (IOException clientClosed) {
                    clientGone = true;
                    break;
                }
            }
            if (!done && failure == null && !clientGone) {
                failure = "upstream closed the stream without [DONE]";
            }
        } catch (IOException e) {
            failure = inv.result().idleTimedOut() ? "upstream stream idle timeout" : "upstream connection lost mid-stream";
        }

        Tokens tokens = Tokens.from(usage);
        if (done) {
            if (tokens == null) {
                tokens = Tokens.estimate(request, content.toString());
                ex.entry.setErrorMessage("provider returned no usage; tokens estimated from text");
            }
            BigDecimal cost = costs.cost(servedModel, tokens.prompt(), tokens.completion());
            fillServed(ex, inv, tokens, cost);
            record(ex); // before [DONE], so usage is queryable the moment the client sees the stream end
            if (cacheDecision.use() && "stop".equals(finishReason) && !content.isEmpty()) {
                cache.store(cacheDecision.scope(), key.key(), ex.tier(), cacheDecision.queryText(),
                        cacheDecision.vector(), assembled(servedModel, content.toString(), finishReason, tokens),
                        inv.target().label(), cost);
            }
            sendQuietly(writer, "[DONE]");
            return;
        }

        if (clientGone) {
            // The provider still generated (and would bill) what was streamed: meter what we can see.
            tokens = tokens != null ? tokens : Tokens.estimate(request, content.toString());
            BigDecimal cost = costs.cost(servedModel, tokens.prompt(), tokens.completion());
            fillServed(ex, inv, tokens, cost);
            ex.entry.setStatus(RequestStatus.CLIENT_DISCONNECTED);
            ex.entry.setErrorMessage("client disconnected mid-stream; usage estimated from streamed text");
            record(ex);
            return;
        }

        // Upstream failed mid-stream: the provider did not deliver, so nothing is billed to the key.
        fillServed(ex, inv, new Tokens(0, 0), BigDecimal.ZERO);
        ex.entry.setStatus(RequestStatus.STREAM_INTERRUPTED);
        ex.entry.setErrorType("upstream_error");
        ex.entry.setErrorMessage(failure);
        record(ex);
        ObjectNode error = mapper.createObjectNode();
        ObjectNode body = error.putObject("error");
        body.put("message", "Upstream stream interrupted: " + failure);
        body.put("type", "upstream_error");
        body.put("code", "stream_interrupted");
        sendQuietly(writer, mapper.writeValueAsString(error));
    }

    private GatewayReply cachedReply(SemanticCache.Hit hit, ChatRequest request, Exchange ex) {
        SemanticCache.Entry entry = hit.entry();
        ObjectNode response = (ObjectNode) mapper.readTree(entry.responseJson());
        response.put("id", "chatcmpl-cache-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        response.put("created", clock.instant().getEpochSecond());
        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);

        ex.entry.setStatus(RequestStatus.CACHE_HIT);
        ex.entry.setHttpStatus(200);
        ex.entry.setCache("hit");
        ex.entry.setCacheSimilarity(Math.round(hit.similarity() * 10_000) / 10_000.0);
        ex.entry.setAttempts("cache:" + entry.servedBy());
        ex.entry.setCostUsd(BigDecimal.ZERO);
        ex.entry.setLatencyMs(ex.elapsedMs());
        record(ex);

        Map<String, String> headers = new LinkedHashMap<>(ex.headers);
        headers.put("x-prism-provider", "cache:" + entry.servedBy());
        headers.put("x-prism-cache", "hit");
        headers.put("x-prism-fallback", "false");
        headers.put("x-prism-cache-similarity", String.valueOf(ex.entry.getCacheSimilarity()));
        if (!request.stream()) {
            headers.put("x-prism-cost-usd", "0");
            return new GatewayReply.Json(200, headers, mapper.writeValueAsString(response));
        }
        headers.put("x-prism-cost-usd", "0");
        return new GatewayReply.Stream(headers, writer -> replay(response, writer));
    }

    /** Replays a cached completion as SSE chunks (one per word) so streaming clients work unchanged. */
    private void replay(ObjectNode response, SseWriter writer) {
        JsonNode choice = response.path("choices").path(0);
        String content = choice.path("message").path("content").asString("");
        String finish = choice.path("finish_reason").asString("stop");
        try {
            writer.data(mapper.writeValueAsString(chunk(response, mapper.createObjectNode().put("role", "assistant").put("content", ""), null)));
            String[] words = content.split(" ");
            for (int i = 0; i < words.length; i++) {
                String piece = i < words.length - 1 ? words[i] + " " : words[i];
                writer.data(mapper.writeValueAsString(chunk(response, mapper.createObjectNode().put("content", piece), null)));
            }
            ObjectNode last = chunk(response, mapper.createObjectNode(), finish);
            last.set("usage", response.path("usage"));
            writer.data(mapper.writeValueAsString(last));
            writer.data("[DONE]");
        } catch (IOException clientClosed) {
            // nothing to clean up: a cache replay holds no upstream connection
        }
    }

    private ObjectNode chunk(ObjectNode response, ObjectNode delta, String finishReason) {
        ObjectNode chunk = mapper.createObjectNode();
        chunk.put("id", response.path("id").asString());
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", response.path("created").asLong());
        chunk.put("model", response.path("model").asString());
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return chunk;
    }

    // ---------------------------------------------------------------- helpers

    private <T> Invocation<T> invoke(List<ModelTarget> targets, ChatRequest request, Exchange ex,
                                     UpstreamInvoker.Call<T> call) {
        try {
            return invoker.invoke(targets, request.body(), call);
        } catch (UpstreamFailure failure) {
            ex.entry.setRetries(failure.retries());
            ex.entry.setAttempts(failure.attempts());
            ex.entry.setUpstreamLatencyMs(failure.upstreamLatencyMs());
            if (failure.callerError()) {
                throw new GatewayException(400, "invalid_request_error", RequestStatus.UPSTREAM_ERROR,
                        "Upstream rejected the request: " + failure.getMessage());
            }
            throw new GatewayException(502, "upstream_error", RequestStatus.UPSTREAM_ERROR,
                    "All providers failed for '" + ex.entry.getRequestedModel() + "': " + failure.attempts()
                            + " (last error: " + failure.getMessage() + ")");
        }
    }

    private void fillServed(Exchange ex, Invocation<?> inv, Tokens tokens, BigDecimal cost) {
        RequestLogEntity e = ex.entry;
        e.setStatus(RequestStatus.OK);
        e.setHttpStatus(200);
        e.setResolvedProvider(inv.target().provider());
        e.setResolvedModel(inv.target().model());
        e.setPromptTokens(tokens.prompt());
        e.setCompletionTokens(tokens.completion());
        e.setCostUsd(cost);
        e.setFallback(inv.fallback());
        e.setRetries(inv.retries());
        e.setAttempts(inv.attempts());
        e.setUpstreamLatencyMs(inv.upstreamLatencyMs());
        e.setLatencyMs(ex.elapsedMs());
    }

    private Map<String, String> servedHeaders(Exchange ex, Invocation<?> inv) {
        Map<String, String> headers = new LinkedHashMap<>(ex.headers);
        headers.put("x-prism-provider", inv.target().label());
        headers.put("x-prism-cache", "miss");
        headers.put("x-prism-fallback", String.valueOf(inv.fallback()));
        if (inv.retries() > 0) {
            headers.put("x-prism-retries", String.valueOf(inv.retries()));
        }
        return headers;
    }

    private GatewayReply reject(GatewayException e, Exchange ex) {
        RequestLogEntity entry = ex.entry;
        entry.setStatus(e.logStatus());
        entry.setHttpStatus(e.httpStatus());
        entry.setErrorType(e.type());
        entry.setErrorMessage(e.getMessage());
        entry.setLatencyMs(ex.elapsedMs());
        record(ex);

        ObjectNode body = mapper.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", e.getMessage());
        error.put("type", e.type());
        error.put("code", e.type());
        error.putNull("param");
        Map<String, String> headers = new LinkedHashMap<>(ex.headers);
        headers.putAll(e.headers());
        return new GatewayReply.Json(e.httpStatus(), headers, mapper.writeValueAsString(body));
    }

    /** Writes the request's single log row and, once its real cost is persisted, frees its budget reservation. */
    private void record(Exchange ex) {
        try {
            recorder.record(ex.entry);
        } catch (RuntimeException e) {
            // The caller already has (or is about to get) its answer; losing a log row must not turn into a 500.
            log.error("Failed to record request {} ({}): {}", ex.requestId, ex.entry.getStatus(), e.toString());
        } finally {
            budgets.release(ex.reservation);
            ex.reservation = null;
        }
    }

    private static boolean isCacheable(JsonNode response) {
        JsonNode choice = response.path("choices").path(0);
        return "stop".equals(choice.path("finish_reason").asString(""))
                && !choice.path("message").path("content").asString("").isBlank();
    }

    private String assembled(String model, String content, String finishReason, Tokens tokens) {
        ObjectNode response = mapper.createObjectNode();
        response.put("id", "chatcmpl-assembled");
        response.put("object", "chat.completion");
        response.put("created", clock.instant().getEpochSecond());
        response.put("model", model);
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", content);
        choice.put("finish_reason", finishReason);
        response.putObject("usage")
                .put("prompt_tokens", tokens.prompt())
                .put("completion_tokens", tokens.completion())
                .put("total_tokens", tokens.prompt() + tokens.completion());
        return mapper.writeValueAsString(response);
    }

    private static void sendQuietly(SseWriter writer, String data) {
        try {
            writer.data(data);
        } catch (IOException ignored) {
            // client already gone; the request is logged regardless
        }
    }

    /** Mutable per-request state threaded through the pipeline. */
    private static final class Exchange {
        final String requestId;
        final long startNanos;
        final Map<String, String> headers = new LinkedHashMap<>();
        RequestLogEntity entry;
        BudgetService.Reservation reservation;

        Exchange(String requestId, long startNanos) {
            this.requestId = requestId;
            this.startNanos = startNanos;
        }

        long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }

        /** The tier/alias that served the request: routed tier for auto, else the requested model. */
        String tier() {
            return entry.getRouteTier() != null ? entry.getRouteTier() : entry.getRequestedModel();
        }
    }

    /** Provider-reported token usage. */
    record Tokens(long prompt, long completion) {

        static Tokens from(JsonNode usage) {
            if (usage == null || !usage.path("prompt_tokens").isNumber() || !usage.path("completion_tokens").isNumber()) {
                return null;
            }
            return new Tokens(usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
        }

        /** Fallback only when the provider reports no usage: whitespace word counts, flagged in the log. */
        static Tokens estimate(ChatRequest request, String completion) {
            long prompt = 0;
            for (JsonNode m : request.body().path("messages")) {
                prompt += words(m.path("content").isString() ? m.path("content").asString() : m.path("content").toString());
            }
            return new Tokens(prompt, words(completion));
        }

        static long words(String text) {
            String t = text == null ? "" : text.trim();
            return t.isEmpty() ? 0 : t.split("\\s+").length;
        }
    }
}
