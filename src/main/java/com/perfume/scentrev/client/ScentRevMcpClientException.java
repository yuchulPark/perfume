package com.perfume.scentrev.client;

/** Safe application diagnostics; SDK exceptions and provider payloads are never attached. */
public class ScentRevMcpClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum FailureType {
        CONFIGURATION,
        INVALID_REQUEST,
        INITIALIZATION,
        AUTHENTICATION,
        TOOL_NOT_FOUND,
        TOOL_CALL,
        RATE_LIMIT,
        SERVER_OUTPUT_SCHEMA,
        INVALID_RESPONSE,
        DTO_CONVERSION,
        TRANSPORT,
        CLOSED
    }

    private final FailureType failureType;
    private final Integer statusCode;
    private final Long retryAfterMs;

    ScentRevMcpClientException(FailureType failureType, Integer statusCode, String message) {
        this(failureType, statusCode, message, null);
    }

    ScentRevMcpClientException(FailureType failureType, Integer statusCode, String message, Long retryAfterMs) {
        super(message);
        this.failureType = failureType;
        this.statusCode = statusCode;
        this.retryAfterMs = retryAfterMs;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    /** Null when the failure has no known HTTP status. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** Provider hint in milliseconds, never an instruction to retry; null when absent or invalid. */
    public Long getRetryAfterMs() {
        return retryAfterMs;
    }
}
