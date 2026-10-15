package com.perfume.ai.recommend;

import org.springframework.http.HttpStatus;

/** A fixed safe diagnostic; never retains provider/SQL messages or request content. */
public class RecommendationFailure extends RuntimeException {
    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Provide a nonblank query of at most 500 characters and an integer topK from 1 to 20."),
        RECOMMEND_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "Recommendations are disabled."),
        RECOMMEND_BUSY(HttpStatus.TOO_MANY_REQUESTS, "Recommendations are busy. No request was queued."),
        RECOMMEND_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The recommendation processing deadline was exceeded."),
        RECOMMEND_INTERRUPTED(HttpStatus.SERVICE_UNAVAILABLE, "Recommendation processing was interrupted."),
        SEARCH_FAILED(HttpStatus.BAD_GATEWAY, "The evidence search could not be completed."),
        SEARCH_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The evidence search timed out."),
        MODEL_MISMATCH(HttpStatus.SERVICE_UNAVAILABLE, "An installed model does not match its pinned identity."),
        LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The local generation server is unavailable."),
        LLM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The generation stage timed out."),
        LLM_HTTP_ERROR(HttpStatus.BAD_GATEWAY, "The local generation server rejected the request."),
        LLM_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "The generation response is invalid."),
        DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The evidence database is unavailable."),
        DATABASE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The evidence database request timed out."),
        EVIDENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Matching, usable evidence is unavailable."),
        GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "A grounded recommendation could not be generated.");
        private final HttpStatus status;
        private final String detail;
        Code(HttpStatus status, String detail) { this.status = status; this.detail = detail; }
        public HttpStatus status() { return status; }
        public String detail() { return detail; }
    }
    private final Code code;
    public RecommendationFailure(Code code) { super(code.detail()); this.code = code; }
    public Code code() { return code; }
}
