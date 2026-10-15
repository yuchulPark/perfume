package com.perfume.ai.search;

import com.perfume.ai.embedding.EmbeddingFailure;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import com.perfume.ai.embedding.EmbeddingVector;
import com.perfume.ai.embedding.PerfumeEmbeddingClient;

/** Reuses the batch's HTTP/digest/vector contract without instantiating any batch runner/store. */
public class OllamaQueryEmbeddingAdapter {
    private final PerfumeEmbeddingClient client;
    private final EmbeddingModelIdentity model;
    public OllamaQueryEmbeddingAdapter(PerfumeEmbeddingClient client, EmbeddingModelIdentity model) { this.client = client; this.model = model; }
    public float[] embed(String query) {
        try {
            var vector = client.embed(model, query); // Existing client verifies tags both before and after inference.
            EmbeddingVector.validate(vector);
            return vector;
        } catch (EmbeddingFailure failure) {
            throw new SemanticSearchFailure(switch (failure.code()) {
                case TIMEOUT -> SemanticSearchFailure.Code.OLLAMA_TIMEOUT;
                case CONNECTION_ERROR -> SemanticSearchFailure.Code.OLLAMA_UNAVAILABLE;
                case MODEL_MISMATCH -> SemanticSearchFailure.Code.MODEL_MISMATCH;
                case INVALID_VECTOR -> SemanticSearchFailure.Code.INVALID_EMBEDDING;
                case HTTP_ERROR -> SemanticSearchFailure.Code.OLLAMA_HTTP_ERROR;
                default -> SemanticSearchFailure.Code.OLLAMA_INVALID_RESPONSE;
            });
        }
    }
}
