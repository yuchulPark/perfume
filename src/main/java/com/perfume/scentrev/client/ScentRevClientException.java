package com.perfume.scentrev.client;

/** Contains a safe diagnostic only, without raw HTTP exceptions, headers, or response bodies. */
public class ScentRevClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum FailureType {
        CONFIGURATION,
        INVALID_REQUEST,
        AUTHENTICATION,
        RATE_LIMIT,
        SERVER,
        TRANSPORT,
        INVALID_RESPONSE
    }

    private final FailureType failureType;
    private final Integer statusCode;

    public ScentRevClientException(FailureType failureType, Integer statusCode, String message) {
        super(message);
        this.failureType = failureType;
        this.statusCode = statusCode;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    /** Null when failure occurs without an HTTP response. */
    public Integer getStatusCode() {
        return statusCode;
    }
}
