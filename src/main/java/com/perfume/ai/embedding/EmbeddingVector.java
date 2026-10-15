package com.perfume.ai.embedding;

import java.util.StringJoiner;

public final class EmbeddingVector {
    private EmbeddingVector() { }

    public static void validate(float[] vector) {
        if (vector == null || vector.length != EmbeddingModelIdentity.DIMENSIONS) {
            throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_VECTOR);
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_VECTOR); }
            norm += (double) value * value;
        }
        if (norm == 0) { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_VECTOR); }
    }

    public static String literal(float[] vector) {
        validate(vector);
        var result = new StringJoiner(",", "[", "]");
        for (float value : vector) { result.add(Float.toString(value)); }
        return result.toString();
    }
}
