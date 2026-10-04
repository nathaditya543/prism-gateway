package com.prism.gateway.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.PrismProperties;

import tools.jackson.databind.ObjectMapper;

/** Builds one adapter per configured provider, sharing a single pooled HTTP client. */
@Component
public class ProviderRegistry implements DisposableBean {

    private final Map<String, ProviderAdapter> adapters = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final PrismProperties.Upstream upstream;

    public ProviderRegistry(GatewayConfig config, PrismProperties props, ObjectMapper mapper) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "upstream-stream-watchdog");
            t.setDaemon(true);
            return t;
        });
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.upstream().connectTimeoutMs()))
                .build();
        this.upstream = props.upstream();
        for (GatewayConfig.ProviderConfig p : config.providers()) {
            register(new OpenAiCompatibleAdapter(p, client, mapper, props.upstream(), scheduler));
        }
    }

    /** Adds (or replaces, e.g. with a test fake) a provider, behind the configured bulkhead. */
    public void register(ProviderAdapter adapter) {
        int limit = upstream.maxInFlightPerProvider();
        adapters.put(adapter.name(), limit > 0
                ? new BulkheadAdapter(adapter, limit, upstream.queueTimeoutMs())
                : adapter);
    }

    /** Calls currently holding a bulkhead slot, per provider (absent when the bulkhead is disabled). */
    public java.util.Map<String, Integer> inFlight() {
        java.util.Map<String, Integer> result = new LinkedHashMap<>();
        adapters.forEach((name, a) -> {
            if (a instanceof BulkheadAdapter b) {
                result.put(name, b.inFlight());
            }
        });
        return result;
    }

    public ProviderAdapter get(String name) {
        ProviderAdapter adapter = adapters.get(name);
        if (adapter == null) {
            throw new IllegalStateException("No adapter for provider " + name);
        }
        return adapter;
    }

    public Iterable<String> names() {
        return adapters.keySet();
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
