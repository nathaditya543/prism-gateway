package com.prism.gateway.gateway;

import com.prism.gateway.usage.RequestStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A validated chat-completions request body. Unknown fields are kept and forwarded upstream. */
public record ChatRequest(ObjectNode body, String model, boolean stream, String lastUserText) {

    private static final java.util.Set<String> ROLES =
            java.util.Set.of("system", "developer", "user", "assistant", "tool", "function");

    public static ChatRequest parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            throw invalid("Request body is empty; expected a JSON chat completion request");
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (JacksonException e) {
            throw invalid("Request body is not valid JSON");
        }
        if (!(node instanceof ObjectNode body)) {
            throw invalid("Request body must be a JSON object");
        }
        JsonNode model = body.path("model");
        if (!model.isString() || model.asString().isBlank()) {
            throw invalid("'model' is required and must be a string");
        }
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw invalid("'messages' must be a non-empty array");
        }
        for (JsonNode m : messages) {
            if (!m.isObject() || !ROLES.contains(m.path("role").asString(""))) {
                throw invalid("each message needs a 'role' of " + ROLES);
            }
        }
        JsonNode stream = body.path("stream");
        if (!stream.isMissingNode() && !stream.isNull() && !stream.isBoolean()) {
            throw invalid("'stream' must be a boolean");
        }
        return new ChatRequest(body, model.asString(), stream.asBoolean(false), lastUserText(messages));
    }

    /** The newest user turn's text (string content, or the text parts of array content). */
    private static String lastUserText(JsonNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if (!"user".equals(m.path("role").asString())) {
                continue;
            }
            JsonNode content = m.path("content");
            if (content.isString()) {
                return content.asString();
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asString())) {
                    sb.append(part.path("text").asString()).append('\n');
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(400, "invalid_request_error", RequestStatus.REJECTED_INVALID, message);
    }
}
