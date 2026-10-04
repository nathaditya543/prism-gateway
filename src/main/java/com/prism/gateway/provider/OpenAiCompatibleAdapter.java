package com.prism.gateway.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import com.prism.gateway.config.GatewayConfig.ProviderConfig;
import com.prism.gateway.config.PrismProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Any upstream that speaks the OpenAI chat-completions wire format (the mocks, OpenAI, Groq, Ollama...). */
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    private static final int MAX_ERROR_BODY = 2_000;

    private final ProviderConfig config;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final PrismProperties.Upstream timeouts;
    private final ScheduledExecutorService scheduler;
    private final URI endpoint;

    public OpenAiCompatibleAdapter(ProviderConfig config, HttpClient client, ObjectMapper mapper,
                                   PrismProperties.Upstream timeouts, ScheduledExecutorService scheduler) {
        this.config = config;
        this.client = client;
        this.mapper = mapper;
        this.timeouts = timeouts;
        this.scheduler = scheduler;
        this.endpoint = URI.create(config.baseUrl() + "/chat/completions");
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public JsonNode complete(ObjectNode body) throws UpstreamException {
        body.remove("stream");
        HttpResponse<String> response = send(body, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw UpstreamException.http(response.statusCode(), errorMessage(response.body()));
        }
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new UpstreamException(UpstreamException.Kind.MALFORMED, 200, "response body is not JSON");
        }
        JsonNode choices = json.path("choices");
        if (!choices.isArray() || choices.isEmpty() || !choices.get(0).path("message").isObject()) {
            throw new UpstreamException(UpstreamException.Kind.MALFORMED, 200, "response has no choices[0].message");
        }
        return json;
    }

    @Override
    public UpstreamStream openStream(ObjectNode body) throws UpstreamException {
        body.put("stream", true);
        HttpResponse<InputStream> response = send(body, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw UpstreamException.http(response.statusCode(), errorMessage(readLimited(response.body())));
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.contains("text/event-stream")) {
            closeQuietly(response.body());
            throw new UpstreamException(UpstreamException.Kind.MALFORMED, 200,
                    "expected text/event-stream, got '" + contentType + "'");
        }
        return new UpstreamStream(response.body(), scheduler, timeouts.streamIdleTimeoutMs());
    }

    private <T> HttpResponse<T> send(ObjectNode body, HttpResponse.BodyHandler<T> handler) throws UpstreamException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeouts.requestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        try {
            return client.send(request, handler);
        } catch (HttpConnectTimeoutException e) {
            throw new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connect timeout");
        } catch (HttpTimeoutException e) {
            throw new UpstreamException(UpstreamException.Kind.TIMEOUT, 0,
                    "no response within " + timeouts.requestTimeoutMs() + " ms");
        } catch (ConnectException e) {
            throw new UpstreamException(UpstreamException.Kind.CONNECT, 0, "connection refused");
        } catch (IOException e) {
            throw new UpstreamException(UpstreamException.Kind.IO, 0,
                    "I/O error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(UpstreamException.Kind.CONNECT, 0, "interrupted");
        }
    }

    /** Pulls {@code error.message} out of an OpenAI-style error body, scrubbed of our credentials. */
    private String errorMessage(String raw) {
        String message = raw == null ? "" : raw;
        try {
            JsonNode node = mapper.readTree(message);
            if (node.path("error").path("message").isString()) {
                message = node.path("error").path("message").asString();
            }
        } catch (JacksonException ignored) {
            // not JSON; keep the raw (truncated) text
        }
        if (config.apiKey() != null && !config.apiKey().isEmpty()) {
            message = message.replace(config.apiKey(), "***");
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    private static String readLimited(InputStream in) {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_ERROR_BODY);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
