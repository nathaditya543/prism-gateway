package com.prism.gateway.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The seam between the gateway core and a model provider. The core only ever sees OpenAI-shaped
 * JSON; an adapter for a different wire format would translate inside its implementation.
 */
public interface ProviderAdapter {

    String name();

    /** Non-streaming completion. {@code body.model} is already the provider's model name. */
    JsonNode complete(ObjectNode body) throws UpstreamException;

    /**
     * Opens a streaming completion. Returns once the upstream accepted the request (status 200),
     * so failures before the first byte can still fail over; later failures surface while reading.
     */
    UpstreamStream openStream(ObjectNode body) throws UpstreamException;
}
