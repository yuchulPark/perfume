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
        INVALID_RESPONSE,
        DTO_CONVERSION,
        TRANSPORT,
        CLOSED
    }

    private final FailureType failureType;
    private final Integer statusCode;

    ScentRevMcpClientException(FailureType failureType, Integer statusCode, String message) {
        super(message);
        this.failureType = failureType;
        this.statusCode = statusCode;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    /** Null when the failure has no known HTTP status. */
    public Integer getStatusCode() {
        return statusCode;
    }
}
