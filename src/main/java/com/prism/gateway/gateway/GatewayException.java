package com.prism.gateway.gateway;

import java.util.Map;

/**
 * A request the gateway refuses or cannot serve. Rendered as an OpenAI-style error body:
 * {@code {"error": {"message", "type", "code"}}}, where {@code type} distinguishes every case.
 */
public class GatewayException extends RuntimeException {

    private final int httpStatus;
    private final String type;
    private final String logStatus;
    private final Map<String, String> headers;

    public GatewayException(int httpStatus, String type, String logStatus, String message) {
        this(httpStatus, type, logStatus, message, Map.of());
    }

    public GatewayException(int httpStatus, String type, String logStatus, String message, Map<String, String> headers) {
        super(message);
        this.httpStatus = httpStatus;
        this.type = type;
        this.logStatus = logStatus;
        this.headers = headers;
    }

    public int httpStatus() { return httpStatus; }
    public String type() { return type; }
    public String logStatus() { return logStatus; }
    public Map<String, String> headers() { return headers; }
}
