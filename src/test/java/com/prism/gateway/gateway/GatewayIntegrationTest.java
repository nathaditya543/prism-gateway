package com.prism.gateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.prism.gateway.provider.FakeProvider;
import com.prism.gateway.provider.ProviderRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end over real HTTP: the full pipeline with in-process fake providers swapped into the
 * provider registry. Each test uses its own tenant from test-seed-keys.json.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:prism-it;DB_CLOSE_DELAY=-1",
        "prism.seed-keys-file=src/test/resources/test-seed-keys.json",
        "prism.admin-token=test-admin"
})
class GatewayIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ProviderRegistry registry;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final FakeProvider alpha = new FakeProvider("alpha");
    private final FakeProvider beta = new FakeProvider("beta");

    @BeforeEach
    void installFakes() {
        registry.register(alpha);
        registry.register(beta);
    }

    @AfterEach
    void resetFakes() {
        alpha.reset();
        beta.reset();
    }

    @Test
    void servesWithTheHeaderContractAndMetersFromProviderUsage() throws Exception {
        HttpResponse<String> res = chat("test-general", "fast", "What is a message queue?", false);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "x-prism-provider")).isEqualTo("alpha/alpha-small");
        assertThat(header(res, "x-prism-cache")).isEqualTo("miss");
        assertThat(header(res, "x-prism-fallback")).isEqualTo("false");
        JsonNode body = mapper.readTree(res.body());
        long prompt = body.path("usage").path("prompt_tokens").asLong();
        long completion = body.path("usage").path("completion_tokens").asLong();
        // alpha-small: $0.15 in / $0.60 out per 1M tokens
        BigDecimal expected = new BigDecimal("0.15").multiply(BigDecimal.valueOf(prompt))
                .add(new BigDecimal("0.60").multiply(BigDecimal.valueOf(completion)))
                .divide(BigDecimal.valueOf(1_000_000));
        assertThat(new BigDecimal(header(res, "x-prism-cost-usd"))).isEqualByComparingTo(expected);

        JsonNode log = latestLog("test-general");
        assertThat(log.path("status").asString()).isEqualTo("ok");
        assertThat(log.path("resolved_model").asString()).isEqualTo("alpha-small");
        assertThat(new BigDecimal(log.path("cost_usd_exact").asString())).isEqualByComparingTo(expected);
    }

    @Test
    void failsOverWhenThePrimaryIsDown() throws Exception {
        alpha.down = true;
        HttpResponse<String> res = chat("test-general", "fast", "failover please", false);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "x-prism-provider")).isEqualTo("beta/beta-small");
        assertThat(header(res, "x-prism-fallback")).isEqualTo("true");
        JsonNode log = latestLog("test-general");
        assertThat(log.path("fallback").asBoolean()).isTrue();
        assertThat(log.path("attempts").asString()).isEqualTo("alpha/alpha-small:503 -> beta/beta-small:ok");
    }

    @Test
    void allProvidersDownIsACleanUpstreamError() throws Exception {
        alpha.down = true;
        beta.down = true;
        HttpResponse<String> res = chat("test-general", "fast", "nobody home", false);
        assertThat(res.statusCode()).isEqualTo(502);
        assertThat(mapper.readTree(res.body()).path("error").path("type").asString()).isEqualTo("upstream_error");
    }

    @Test
    void streamsChunksAndTerminatesWithDone() throws Exception {
        HttpResponse<String> res = chat("test-general", "fast", "Explain server-sent events", true);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "content-type")).contains("text/event-stream");
        assertThat(header(res, "x-prism-provider")).isEqualTo("alpha/alpha-small");
        List<String> data = res.body().lines().filter(l -> l.startsWith("data: ")).toList();
        assertThat(data.size()).isGreaterThan(3);
        assertThat(data.get(data.size() - 1)).isEqualTo("data: [DONE]");

        JsonNode log = latestLog("test-general");
        assertThat(log.path("stream").asBoolean()).isTrue();
        assertThat(log.path("status").asString()).isEqualTo("ok");
        assertThat(log.path("completion_tokens").asLong()).isPositive();
        assertThat(new BigDecimal(log.path("cost_usd_exact").asString())).isPositive();
    }

    @Test
    void midStreamFailureEndsWithAnErrorEventNotASplice() throws Exception {
        alpha.breakStreamAfterChunks = 3;
        HttpResponse<String> res = chat("test-general", "fast", "this stream will break midway", true);
        List<String> data = res.body().lines().filter(l -> l.startsWith("data: ")).toList();
        assertThat(data).hasSize(4); // 3 content chunks + the error event
        assertThat(data.get(3)).contains("\"stream_interrupted\"");
        assertThat(res.body()).doesNotContain("[DONE]").doesNotContain("[beta:");
        assertThat(beta.calls.get()).isZero();
        assertThat(latestLog("test-general").path("status").asString()).isEqualTo("stream_interrupted");
    }

    @Test
    void semanticCacheHitsParaphrasesButNeverAcrossTenants() throws Exception {
        String tag = " (it" + System.nanoTime() + ")";
        String prompt = "How do I reset my password on the dashboard?" + tag;
        String paraphrase = "What are the steps to reset my dashboard password?" + tag;

        assertThat(header(chat("test-cache-a", "fast", prompt, false), "x-prism-cache")).isEqualTo("miss");
        HttpResponse<String> repeat = chat("test-cache-a", "fast", paraphrase, false);
        assertThat(header(repeat, "x-prism-cache")).isEqualTo("hit");
        assertThat(header(repeat, "x-prism-cost-usd")).isEqualTo("0");
        // Same prompt, different tenant: must not be served tenant A's response.
        assertThat(header(chat("test-cache-b", "fast", prompt, false), "x-prism-cache")).isEqualTo("miss");
        assertThat(alpha.calls.get()).isEqualTo(2);
    }

    @Test
    void cachedResponsesReplayAsStreams() throws Exception {
        String prompt = "What is consistent hashing? (replay-" + System.nanoTime() + ")";
        chat("test-cache-a", "fast", prompt, false);
        HttpResponse<String> res = chat("test-cache-a", "fast", prompt, true);
        assertThat(header(res, "x-prism-cache")).isEqualTo("hit");
        assertThat(res.body()).endsWith("data: [DONE]\n\n");
    }

    @Test
    void rateLimitNeverOverAdmitsUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            results.add(pool.submit((Callable<Integer>) () -> chat("test-limited", "fast", "burst " + n, false).statusCode()));
        }
        int ok = 0;
        int limited = 0;
        for (Future<Integer> f : results) {
            int status = f.get();
            ok += status == 200 ? 1 : 0;
            limited += status == 429 ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(ok).isEqualTo(5);
        assertThat(limited).isEqualTo(15);

        JsonNode usage = admin("/admin/usage?key=test-limited");
        assertThat(usage.path("requests").asLong()).isEqualTo(5);
        assertThat(usage.path("rejected").asLong()).isEqualTo(15);
    }

    @Test
    void budgetIsEnforcedAfterItIsSpent() throws Exception {
        // ~30 prompt words + an echoing reply costs about $0.00002, double the $0.00001 budget.
        String longPrompt = "Summarize the following incident report in three sentences for an executive audience "
                + "covering impact duration root cause and the follow up actions agreed by the team";
        assertThat(chat("test-broke", "fast", longPrompt, false).statusCode()).isEqualTo(200);
        HttpResponse<String> second = chat("test-broke", "fast", "second request", false);
        assertThat(second.statusCode()).isEqualTo(402);
        assertThat(mapper.readTree(second.body()).path("error").path("type").asString()).isEqualTo("budget_exceeded");
        assertThat(latestLog("test-broke").path("status").asString()).isEqualTo("rejected_budget");
    }

    @Test
    void concurrentBurstCannotOvershootABudgetThatCoversOneRequest() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            // Long enough that one request costs more than the whole budget, so the outcome cannot depend on
            // timing: any request admitted after the first finished would be over budget either way.
            String prompt = "Summarize the following incident report in three sentences for an executive audience "
                    + "covering impact duration root cause and the follow up actions agreed by the team " + n;
            results.add(pool.submit((Callable<Integer>) () ->
                    chat("test-broke-burst", "fast", prompt, false).statusCode()));
        }
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : results) {
            int status = f.get();
            ok += status == 200 ? 1 : 0;
            rejected += status == 402 ? 1 : 0;
        }
        pool.shutdownNow();
        // Without reservations every request that arrives before the first is billed would be admitted.
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(9);
    }

    @Test
    void rejectionsAreDistinctAndLogged() throws Exception {
        assertError(chat("not-a-key", "fast", "hi", false), 401, "authentication_error");
        assertError(chat("test-cache-a", "smart", "hi", false), 403, "model_not_allowed");
        assertError(chat("test-general", "no-such-model", "hi", false), 404, "not_found_error");
        HttpResponse<String> malformed = post("test-general", "{\"model\":\"fast\",\"messages\":[]}");
        assertError(malformed, 400, "invalid_request_error");
        assertThat(latestLog("test-general").path("status").asString()).isEqualTo("rejected_invalid");
    }

    @Test
    void autoRoutesByDifficultyAndLogsTheReason() throws Exception {
        HttpResponse<String> hard = chat("test-general", "auto", "Prove that the square root of 2 is irrational.", false);
        assertThat(header(hard, "x-prism-provider")).isEqualTo("alpha/alpha-large");
        JsonNode log = latestLog("test-general");
        assertThat(log.path("route_tier").asString()).isEqualTo("smart");
        assertThat(log.path("route_reason").asString()).contains("formal proof");

        HttpResponse<String> easy = chat("test-general", "auto", "What is the capital of France?", false);
        assertThat(header(easy, "x-prism-provider")).isEqualTo("alpha/alpha-small");
    }

    @Test
    void adminApiRequiresTheAdminToken() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/admin/logs"))
                .header("Authorization", "Bearer test-general").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> chat(String key, String model, String prompt, boolean stream) throws Exception {
        var body = mapper.createObjectNode().put("model", model);
        if (stream) {
            body.put("stream", true);
        }
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        return post(key, mapper.writeValueAsString(body));
    }

    private HttpResponse<String> post(String key, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/v1/chat/completions"))
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode admin(String path) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri(path))
                .header("X-Admin-Token", "test-admin").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return mapper.readTree(res.body());
    }

    private JsonNode latestLog(String key) throws Exception {
        return admin("/admin/logs?limit=1&key=" + key).get(0);
    }

    private void assertError(HttpResponse<String> res, int status, String type) {
        assertThat(res.statusCode()).isEqualTo(status);
        assertThat(mapper.readTree(res.body()).path("error").path("type").asString()).isEqualTo(type);
    }

    private static String header(HttpResponse<?> res, String name) {
        return res.headers().firstValue(name).orElse(null);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
