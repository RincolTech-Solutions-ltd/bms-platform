package com.rincoltech.bms.core.ai;

/**
 * A call to the provider that gave no usable answer. It carries a closed {@link Kind} and, for an
 * HTTP answer, the status number, and nothing else: the provider's error body, a URL, a header or
 * a JDK exception message can all quote data or the key, so none is kept (spec 5.10).
 */
public class LlmException extends Exception {

    /** What went wrong, in a closed vocabulary that is also the call log's reason. */
    public enum Kind {
        /** The total budget ran out or the provider did not answer in time. */
        TIMEOUT("timeout", true),
        /** Could not connect, or the connection broke. */
        NETWORK("network", true),
        /** HTTP 429 after the retries. */
        RATE_LIMITED("rate_limited", true),
        /** HTTP 5xx after the retries. */
        PROVIDER_ERROR("provider_error", true),
        /** HTTP 401 or 403: the key is wrong or revoked. */
        AUTH("auth_error", true),
        /** The provider answered 200 with something that is not a chat completion. */
        BAD_RESPONSE("bad_response", true),
        /** Another 4xx: the request is at fault, not the provider's health. */
        BAD_REQUEST("bad_request", false),
        /** No provider is configured. */
        UNAVAILABLE("provider_off", false);

        private final String reason;
        private final boolean countsAgainstBreaker;

        Kind(String reason, boolean countsAgainstBreaker) {
            this.reason = reason;
            this.countsAgainstBreaker = countsAgainstBreaker;
        }

        /** The value stored in {@code ai_calls.reason}. */
        public String reason() {
            return reason;
        }

        /** Whether this failure says the provider is unhealthy (and so feeds the circuit breaker). */
        public boolean countsAgainstBreaker() {
            return countsAgainstBreaker;
        }
    }

    private final Kind kind;
    private final int httpStatus;
    private final int attempts;

    public LlmException(Kind kind, int httpStatus, int attempts) {
        super(kind.reason() + (httpStatus > 0 ? " (HTTP " + httpStatus + ")" : ""));
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.attempts = attempts;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status of the last answer, or 0 when there was none. */
    public int httpStatus() {
        return httpStatus;
    }

    /** How many HTTP attempts were made, counting the first. */
    public int attempts() {
        return attempts;
    }
}
