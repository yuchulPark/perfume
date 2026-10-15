package com.perfume.ai.embedding;

public interface PerfumeEmbeddingClient {
    void verifyModel(EmbeddingModelIdentity model);
    float[] embed(EmbeddingModelIdentity model, String text);
}
