package com.prism.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.prism.gateway.catalog.ModelTarget;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class UpstreamInvokerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final FakeProvider alpha = new FakeProvider("alpha");
    private final FakeProvider beta = new FakeProvider("beta");
    private final List<ModelTarget> chain = List.of(
            new ModelTarget("alpha", "alpha-small"), new ModelTarget("beta", "beta-small"));
    private final MutableClock clock = new MutableClock();
    private CircuitBreakers breakers;
    private UpstreamInvoker invoker;

    @BeforeEach
    void setUp() {
        GatewayConfig config = new GatewayConfig(List.of(), Map.of(), null,
                new GatewayConfig.RetryPolicy(3, 1, 2.0)); // 1ms backoff keeps the test fast
        PrismProperties props = new PrismProperties("", "", "", "", "t", Map.of(),
                new PrismProperties.Upstream(100, 100, 100, 0, 100), new PrismProperties.Cache(1, 1));
        ProviderRegistry registry = new ProviderRegistry(config, props, mapper);
        registry.register(alpha);
        registry.register(beta);
        breakers = new CircuitBreakers(clock);
        invoker = new UpstreamInvoker(registry, new ProviderHealthTracker(clock), breakers, config);
    }

    private UpstreamInvoker.Invocation<JsonNode> call() {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return invoker.invoke(chain, body, ProviderAdapter::complete);
    }

    @Test
    void healthyPrimaryServesWithoutFallback() {
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("alpha");
        assertThat(inv.fallback()).isFalse();
        assertThat(inv.retries()).isZero();
    }

    @Test
    void transientErrorsAreRetriedOnTheSameProvider() {
        alpha.failNext(UpstreamException.http(500, "boom")).failNext(UpstreamException.http(502, "boom"));
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("alpha");
        assertThat(inv.retries()).isEqualTo(2);
        assertThat(inv.attempts()).isEqualTo("alpha/alpha-small:500 -> alpha/alpha-small:502 -> alpha/alpha-small:ok");
    }

    @Test
    void retriesAreBoundedThenFailOver() {
        for (int i = 0; i < 3; i++) {
            alpha.failNext(UpstreamException.http(500, "boom"));
        }
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("beta");
        assertThat(inv.fallback()).isTrue();
        assertThat(alpha.calls.get()).isEqualTo(3);
    }

    @Test
    void downOrRateLimitedProvidersFailOverImmediately() {
        alpha.down = true;
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("beta");
        assertThat(inv.fallback()).isTrue();
        assertThat(alpha.calls.get()).as("no retries against a 503").isEqualTo(1);

        alpha.reset();
        alpha.failNext(UpstreamException.http(429, "slow down"));
        assertThat(call().target().provider()).isEqualTo("beta");
    }

    @Test
    void timeoutsFailOverWithoutRetrying() {
        alpha.failNext(new UpstreamException(UpstreamException.Kind.TIMEOUT, 0, "timeout"));
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("beta");
        assertThat(alpha.calls.get()).isEqualTo(1);
    }

    @Test
    void brokenConnectionsAreRetriedOnTheSameProvider() {
        alpha.failNext(new UpstreamException(UpstreamException.Kind.IO, 0, "connection reset"));
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("alpha");
        assertThat(inv.attempts()).isEqualTo("alpha/alpha-small:io_error -> alpha/alpha-small:ok");
    }

    @Test
    void chainIsWalkedAgainWhenEveryProviderRefusedConnections() {
        // A momentarily full accept backlog on both providers looks like refused connections everywhere.
        alpha.failNext(new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused"));
        beta.failNext(new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused"));
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("alpha");
        assertThat(inv.fallback()).isFalse();
        assertThat(inv.attempts()).isEqualTo(
                "alpha/alpha-small:connect_error -> beta/beta-small:connect_error -> alpha/alpha-small:ok");
    }

    @Test
    void refusalFromAProviderThatJustAnsweredIsRetriedNotFailedOver() {
        call(); // alpha answers: it is up
        alpha.failNext(new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused"));
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("alpha");
        assertThat(inv.attempts()).isEqualTo("alpha/alpha-small:connect_error -> alpha/alpha-small:ok");

        clock.millis.addAndGet(UpstreamInvoker.RECENT_SUCCESS_MS + 1); // no recent success any more
        alpha.failNext(new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused"));
        assertThat(call().target().provider()).as("a quiet provider that refuses is treated as down").isEqualTo("beta");
    }

    @Test
    void secondChainPassIsBoundedAndSkippedForHttpOutages() {
        for (int i = 0; i < 2; i++) {
            alpha.failNext(new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused"));
            beta.failNext(new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused"));
        }
        assertThatThrownBy(this::call).isInstanceOf(UpstreamInvoker.UpstreamFailure.class);
        assertThat(alpha.calls.get() + beta.calls.get()).as("exactly two passes").isEqualTo(4);

        alpha.reset();
        beta.reset();
        alpha.down = true;
        beta.down = true;
        assertThatThrownBy(this::call).isInstanceOf(UpstreamInvoker.UpstreamFailure.class);
        assertThat(alpha.calls.get() + beta.calls.get()).as("503s are a real outage: one pass").isEqualTo(2);
    }

    @Test
    void circuitOpensAfterConsecutiveFailuresSoTheDeadProviderIsSkipped() {
        alpha.down = true;
        for (int i = 0; i < CircuitBreakers.FAILURE_THRESHOLD; i++) {
            call();
        }
        int alphaCalls = alpha.calls.get();
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(alpha.calls.get()).as("alpha not even tried").isEqualTo(alphaCalls);
        assertThat(inv.attempts()).isEqualTo("alpha/alpha-small:circuit_open -> beta/beta-small:ok");
        assertThat(inv.fallback()).isTrue();
    }

    @Test
    void afterTheCooldownOneProbeClosesTheCircuitAndTrafficReturnsToThePrimary() {
        alpha.down = true;
        for (int i = 0; i < CircuitBreakers.FAILURE_THRESHOLD; i++) {
            call();
        }
        alpha.down = false; // provider restored, but the circuit is still open
        assertThat(call().target().provider()).isEqualTo("beta");

        clock.millis.addAndGet(CircuitBreakers.COOLDOWN_MS);
        UpstreamInvoker.Invocation<JsonNode> probe = call();
        assertThat(probe.target().provider()).isEqualTo("alpha");
        assertThat(probe.fallback()).isFalse();
        assertThat(breakers.states(List.of("alpha")).get("alpha")).isEqualTo(CircuitBreakers.State.CLOSED);
    }

    @Test
    void failedProbeReopensTheCircuit() {
        alpha.down = true;
        for (int i = 0; i < CircuitBreakers.FAILURE_THRESHOLD; i++) {
            call();
        }
        clock.millis.addAndGet(CircuitBreakers.COOLDOWN_MS);
        assertThat(call().target().provider()).isEqualTo("beta"); // the probe hit alpha, failed, beta served
        int alphaCalls = alpha.calls.get();
        call();
        assertThat(alpha.calls.get()).as("re-opened: skipped again").isEqualTo(alphaCalls);
    }

    @Test
    void whenEveryCircuitIsOpenTheChainIsStillTried() {
        alpha.down = true;
        beta.down = true;
        for (int i = 0; i < CircuitBreakers.FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(this::call).isInstanceOf(UpstreamInvoker.UpstreamFailure.class);
        }
        alpha.down = false;
        beta.down = false;
        UpstreamInvoker.Invocation<JsonNode> inv = call();
        assertThat(inv.target().provider()).isEqualTo("alpha");
    }

    @Test
    void bulkheadMakesExtraCallersFailOverInsteadOfPilingOn() throws Exception {
        BulkheadAdapter bulkhead = new BulkheadAdapter(alpha, 1, 50);
        ObjectNode body = mapper.createObjectNode();
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        UpstreamStream held = bulkhead.openStream(body.deepCopy().put("model", "alpha-small"));
        assertThat(bulkhead.inFlight()).isEqualTo(1);
        assertThatThrownBy(() -> bulkhead.complete(body.deepCopy().put("model", "alpha-small")))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(UpstreamException.Kind.SATURATED);
                    assertThat(e.disposition()).isEqualTo(UpstreamException.Disposition.FAILOVER);
                });
        held.close();
        assertThat(bulkhead.inFlight()).isZero();
        assertThat(bulkhead.complete(body.deepCopy().put("model", "alpha-small"))).isNotNull();
    }

    @Test
    void callerErrorsAreNotRetriedAnywhere() {
        alpha.failNext(UpstreamException.http(400, "bad temperature"));
        assertThatThrownBy(this::call)
                .isInstanceOfSatisfying(UpstreamInvoker.UpstreamFailure.class, f -> assertThat(f.callerError()).isTrue());
        assertThat(beta.calls.get()).isZero();
    }

    @Test
    void allProvidersDownIsAnUpstreamFailureWithTheFullTrail() {
        alpha.down = true;
        beta.down = true;
        assertThatThrownBy(this::call)
                .isInstanceOfSatisfying(UpstreamInvoker.UpstreamFailure.class, f -> {
                    assertThat(f.callerError()).isFalse();
                    assertThat(f.attempts()).isEqualTo("alpha/alpha-small:503 -> beta/beta-small:503");
                });
    }
}
