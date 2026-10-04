package com.prism.gateway.provider;

/**
 * A failed upstream call, classified so the invoker knows whether to retry the same provider,
 * fail over to the next one, or give up and surface the error to the caller.
 */
public class UpstreamException extends Exception {

    /**
     * CONNECT = the provider refused or could not be reached; IO = a connection that was open or
     * being opened broke (reset, early EOF on a stale pooled keep-alive connection, full accept backlog).
     */
    public enum Kind { TIMEOUT, CONNECT, IO, HTTP, MALFORMED, SATURATED }

    public enum Disposition {
        /** Transient: retry the same provider with backoff. */
        RETRY,
        /** Provider is down, rate-limited, slow or misconfigured: move to the next in the chain. */
        FAILOVER,
        /** The request itself is bad; another provider would reject it too. */
        FATAL
    }

    private final Kind kind;
    private final int status;

    public UpstreamException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public static UpstreamException http(int status, String message) {
        return new UpstreamException(Kind.HTTP, status, message);
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    /**
     * <ul>
     *   <li>500/502/504, malformed bodies and broken connections (resets, early EOF) are transient
     *       blips: retried with backoff.</li>
     *   <li>503 (down), 429 (rate-limited), connection refused and timeouts fail over immediately:
     *       retrying a dead or saturated provider only adds latency, and a provider that just
     *       timed out would likely time out again. (The invoker makes one exception: a refusal from a
     *       provider that succeeded within the last 2 s means a full accept queue, so it is retried.)</li>
     *   <li>401/403/404 mean this provider is misconfigured for the model: fail over.</li>
     *   <li>Other 4xx (e.g. 400 invalid parameters) are the caller's fault: fatal.</li>
     * </ul>
     */
    public Disposition disposition() {
        return switch (kind) {
            case TIMEOUT, CONNECT, SATURATED -> Disposition.FAILOVER;
            case IO, MALFORMED -> Disposition.RETRY;
            case HTTP -> switch (status) {
                case 500, 502, 504 -> Disposition.RETRY;
                case 503, 429, 401, 403, 404 -> Disposition.FAILOVER;
                default -> status >= 500 ? Disposition.RETRY : Disposition.FATAL;
            };
        };
    }

    /** Short form for the request log's attempt trail. */
    public String shortCode() {
        return switch (kind) {
            case TIMEOUT -> "timeout";
            case CONNECT -> "connect_error";
            case IO -> "io_error";
            case SATURATED -> "saturated";
            case MALFORMED -> "malformed";
            case HTTP -> String.valueOf(status);
        };
    }
}
