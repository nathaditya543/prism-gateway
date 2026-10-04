package com.prism.gateway.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-process provider for tests. Replies like the pack's mock (word-count usage), and can be told
 * to fail: a queue of scripted errors is consumed first, then {@link #down} applies.
 */
public class FakeProvider implements ProviderAdapter {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fake-provider-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final String name;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Deque<UpstreamException> scripted = new ArrayDeque<>();
    public volatile boolean down;
    /** When > 0, streams emit this many content chunks and then the connection breaks. */
    public volatile int breakStreamAfterChunks;
    public final AtomicInteger calls = new AtomicInteger();

    public FakeProvider(String name) {
        this.name = name;
    }

    public synchronized FakeProvider failNext(UpstreamException e) {
        scripted.add(e);
        return this;
    }

    public void reset() {
        synchronized (this) {
            scripted.clear();
        }
        down = false;
        breakStreamAfterChunks = 0;
        calls.set(0);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JsonNode complete(ObjectNode body) throws UpstreamException {
        maybeFail();
        String model = body.path("model").asString();
        String reply = reply(model, body);
        ObjectNode response = mapper.createObjectNode();
        response.put("id", "chatcmpl-fake");
        response.put("object", "chat.completion");
        response.put("model", model);
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", reply);
        choice.put("finish_reason", "stop");
        long prompt = promptTokens(body);
        long completion = reply.split(" ").length;
        response.putObject("usage").put("prompt_tokens", prompt).put("completion_tokens", completion)
                .put("total_tokens", prompt + completion);
        return response;
    }

    @Override
    public UpstreamStream openStream(ObjectNode body) throws UpstreamException {
        maybeFail();
        String model = body.path("model").asString();
        String[] words = reply(model, body).split(" ");
        StringBuilder sse = new StringBuilder();
        int limit = breakStreamAfterChunks > 0 ? breakStreamAfterChunks : words.length;
        for (int i = 0; i < Math.min(limit, words.length); i++) {
            sse.append("data: ").append(chunk(model, words[i] + " ", null)).append("\n\n");
        }
        InputStream in = new ByteArrayInputStream(sse.toString().getBytes(StandardCharsets.UTF_8));
        if (breakStreamAfterChunks > 0) {
            in = new SequenceInputStream(in, new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("connection reset by fake provider");
                }
            });
        } else {
            long prompt = promptTokens(body);
            ObjectNode last = (ObjectNode) mapper.readTree(chunk(model, null, "stop"));
            last.putObject("usage").put("prompt_tokens", prompt).put("completion_tokens", words.length)
                    .put("total_tokens", prompt + words.length);
            String tail = "data: " + mapper.writeValueAsString(last) + "\n\ndata: [DONE]\n\n";
            in = new SequenceInputStream(in, new ByteArrayInputStream(tail.getBytes(StandardCharsets.UTF_8)));
        }
        return new UpstreamStream(in, SCHEDULER, 5_000);
    }

    private synchronized void maybeFail() throws UpstreamException {
        calls.incrementAndGet();
        UpstreamException next = scripted.poll();
        if (next != null) {
            throw next;
        }
        if (down) {
            throw UpstreamException.http(503, "Provider is down (fake)");
        }
    }

    private String reply(String model, ObjectNode body) {
        JsonNode messages = body.path("messages");
        String last = messages.get(messages.size() - 1).path("content").asString();
        return "[" + name + ":" + model + "] answer about " + last;
    }

    private long promptTokens(ObjectNode body) {
        long n = 0;
        for (JsonNode m : body.path("messages")) {
            n += m.path("content").asString("").trim().split("\\s+").length;
        }
        return n;
    }

    private String chunk(String model, String content, String finish) {
        ObjectNode chunk = mapper.createObjectNode();
        chunk.put("id", "chatcmpl-fake");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", model);
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (content != null) {
            delta.put("content", content);
        }
        if (finish == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finish);
        }
        return mapper.writeValueAsString(chunk);
    }
}
