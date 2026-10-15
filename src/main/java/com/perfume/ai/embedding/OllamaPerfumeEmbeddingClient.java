package com.perfume.ai.embedding;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Uses only the local tags/embed endpoints; no pull, generation or automatic retries. */
public class OllamaPerfumeEmbeddingClient implements PerfumeEmbeddingClient {
    private final RestClient client;

    public OllamaPerfumeEmbeddingClient(RestClient client) { this.client = client; }

    @Override
    public void verifyModel(EmbeddingModelIdentity model) {
        var response = request(() -> client.get().uri("/api/tags").retrieve().body(JsonNode.class));
        if (response == null || response.has("error") || !response.path("models").isArray()) { invalid(); }
        int matches = 0;
        for (var installed : response.path("models")) {
            if (model.name().equals(installed.path("name").asText()) || model.name().equals(installed.path("model").asText())) {
                matches++;
                if (!installed.path("digest").isTextual()) { throw new EmbeddingFailure(EmbeddingFailure.Code.MODEL_MISMATCH); }
                try {
                    if (!model.digest().equals(EmbeddingModelIdentity.normalizeDigest(installed.path("digest").asText()))) {
                        throw new EmbeddingFailure(EmbeddingFailure.Code.MODEL_MISMATCH);
                    }
                } catch (IllegalArgumentException malformed) { throw new EmbeddingFailure(EmbeddingFailure.Code.MODEL_MISMATCH); }
            }
        }
        if (matches != 1) { throw new EmbeddingFailure(EmbeddingFailure.Code.MODEL_MISMATCH); }
    }

    @Override
    public float[] embed(EmbeddingModelIdentity model, String text) {
        verifyModel(model);
        var response = request(() -> client.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model.name(), "input", List.of(text), "truncate", false,
                        "dimensions", EmbeddingModelIdentity.DIMENSIONS, "keep_alive", "5m"))
                .retrieve().body(JsonNode.class));
        if (response == null || response.has("error") || !model.name().equals(response.path("model").asText())
                || !response.path("embeddings").isArray() || response.path("embeddings").size() != 1) { invalid(); }
        var numbers = response.path("embeddings").get(0);
        if (!numbers.isArray() || numbers.size() != EmbeddingModelIdentity.DIMENSIONS) {
            throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_VECTOR);
        }
        var vector = new float[EmbeddingModelIdentity.DIMENSIONS];
        for (int index = 0; index < vector.length; index++) {
            var number = numbers.get(index);
            if (!number.isNumber() || !Double.isFinite(number.doubleValue())) {
                throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_VECTOR);
            }
            vector[index] = number.floatValue();
        }
        EmbeddingVector.validate(vector);
        // A mutable tag must still point at the pinned model after inference.
        verifyModel(model);
        return vector;
    }

    private static JsonNode request(Supplier<JsonNode> call) {
        try { return call.get(); }
        catch (RestClientResponseException status) { throw new EmbeddingFailure(EmbeddingFailure.Code.HTTP_ERROR); }
        catch (ResourceAccessException transport) {
            for (Throwable cause = transport; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                    throw new EmbeddingFailure(EmbeddingFailure.Code.TIMEOUT);
                }
            }
            throw new EmbeddingFailure(EmbeddingFailure.Code.CONNECTION_ERROR);
        }
        catch (RestClientException malformed) { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_RESPONSE); }
    }

    private static void invalid() { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_RESPONSE); }
}
