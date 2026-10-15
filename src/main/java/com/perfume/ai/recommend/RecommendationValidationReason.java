package com.perfume.ai.recommend;

/** Internal allowlisted diagnostics. Never returned in the public recommendation error response. */
enum RecommendationValidationReason {
    INVALID_JSON, INVALID_SCHEMA, MISSING_REQUIRED_FIELD, UNEXPECTED_FIELD, INVALID_FIELD_TYPE,
    INVALID_IDENTIFIER, UNKNOWN_SOURCE, UNKNOWN_FACT_ID, DUPLICATE_SOURCE,
    INVALID_SELECTION_COUNT, INVALID_SELECTION_ORDER, EVIDENCE_MISMATCH, RESPONSE_TOO_LONG
}
