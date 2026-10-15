package com.perfume.ai.embedding;

/** Public diagnostics contain only a fixed code, never response bodies, SQL or credentials. */
public class EmbeddingFailure extends RuntimeException {
    public enum Code {
        BATCH_BUSY, DATABASE, SOURCE_MISSING, SOURCE_CHANGED, INVALID_SOURCE,
        HTTP_ERROR, TIMEOUT, CONNECTION_ERROR, INVALID_RESPONSE, MODEL_MISMATCH,
        INVALID_VECTOR, PARTIAL_FAILURE
    }

    private final Code code;
    private final PerfumeEmbeddingBatch.Result result;

    public EmbeddingFailure(Code code) {
        this(code, null);
    }

    public EmbeddingFailure(Code code, PerfumeEmbeddingBatch.Result result) {
        super("Perfume embedding operation failed: " + code);
        this.code = code;
        this.result = result;
    }

    public Code code() { return code; }
    public PerfumeEmbeddingBatch.Result result() { return result; }
}
