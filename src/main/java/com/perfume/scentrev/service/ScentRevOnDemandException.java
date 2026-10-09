package com.perfume.scentrev.service;

/** Fixed public diagnostics only: no provider/SQL message, query text, credentials or cause chain. */
public class ScentRevOnDemandException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum FailureType {
        INVALID_QUERY("Search requires at least 2 nonblank query characters."),
        INVALID_LIMIT("Search limit must be between 1 and 10."),
        INVALID_SLUG("A valid canonical fragrance slug is required."),
        PROVIDER_CONFIGURATION("ScentRev credentials are not configured."),
        PROVIDER_AUTHENTICATION("ScentRev authentication failed."),
        PROVIDER_SEARCH_FAILURE("ScentRev candidate search failed."),
        PROFILE_LOOKUP_FAILURE("ScentRev selected profile lookup failed."),
        CANONICAL_IDENTITY_MISMATCH("The returned profile does not have the requested canonical identity."),
        IDENTIFIER_CONFLICT("The selected fragrance identifiers conflict with cached data."),
        PERSISTENCE_FAILURE("Selected perfume cache access or import failed; retry after resolving the cause.");

        private final String message;
        FailureType(String message) { this.message = message; }
    }
    private final FailureType failureType;
    private ScentRevOnDemandException(FailureType type) { super(type.message); failureType = type; }
    public static ScentRevOnDemandException failure(FailureType type) { return new ScentRevOnDemandException(type); }
    public FailureType getFailureType() { return failureType; }
}
