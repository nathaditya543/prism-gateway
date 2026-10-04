package com.prism.gateway.usage;

/** Values of {@code request_logs.status}. */
public final class RequestStatus {

    public static final String OK = "ok";
    public static final String CACHE_HIT = "cache_hit";
    public static final String REJECTED_AUTH = "rejected_auth";
    public static final String REJECTED_INVALID = "rejected_invalid";
    public static final String REJECTED_UNKNOWN_MODEL = "rejected_unknown_model";
    public static final String REJECTED_ALLOWLIST = "rejected_allowlist";
    public static final String REJECTED_RATE_LIMIT = "rejected_rate_limit";
    public static final String REJECTED_BUDGET = "rejected_budget";
    public static final String UPSTREAM_ERROR = "upstream_error";
    /** The upstream stream broke after the first byte was sent to the client. */
    public static final String STREAM_INTERRUPTED = "stream_interrupted";
    /** The client hung up mid-stream. */
    public static final String CLIENT_DISCONNECTED = "client_disconnected";

    private RequestStatus() {
    }
}
