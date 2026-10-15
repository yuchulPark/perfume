package com.perfume.ai.search;

import org.springframework.http.HttpStatus;

/** Safe boundary diagnostics. Never retains an underlying HTTP/SQL exception or request text. */
public class SemanticSearchFailure extends RuntimeException {
    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Provide a nonblank query of at most 500 characters and an integer topK from 1 to 20."),
        SEARCH_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "Semantic search is disabled."),
        SEARCH_BUSY(HttpStatus.TOO_MANY_REQUESTS, "Semantic search is busy. No request was queued."),
        SEARCH_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Semantic search exceeded its processing deadline."),
        SEARCH_INTERRUPTED(HttpStatus.SERVICE_UNAVAILABLE, "Semantic search was interrupted."),
        SEARCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Semantic search could not be completed."),
        OLLAMA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The local embedding server is unavailable."),
        OLLAMA_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The local embedding request timed out."),
        OLLAMA_HTTP_ERROR(HttpStatus.BAD_GATEWAY, "The local embedding server rejected the request."),
        OLLAMA_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "The local embedding server returned an invalid response."),
        MODEL_MISMATCH(HttpStatus.SERVICE_UNAVAILABLE, "The installed embedding model does not match the pinned identity."),
        INVALID_EMBEDDING(HttpStatus.BAD_GATEWAY, "The embedding vector is invalid or is not 1024-dimensional."),
        DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The search database is unavailable."),
        DATABASE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The database search timed out."),
        PGVECTOR_SEARCH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The vector search could not be completed.");
        private final HttpStatus status;
        private final String detail;
        Code(HttpStatus status, String detail) { this.status = status; this.detail = detail; }
        public HttpStatus status() { return status; }
        public String detail() { return detail; }
    }
    private final Code code;
    public SemanticSearchFailure(Code code) { super(code.detail()); this.code = code; }
    public Code code() { return code; }
}
