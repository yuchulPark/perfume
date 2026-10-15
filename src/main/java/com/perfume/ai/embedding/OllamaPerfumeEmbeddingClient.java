package com.perfume.ai.embedding;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Uses only the local tags/embed endpoints; no pull, generation or automatic retries. */
public class OllamaPerfumeEmbeddingClient implements PerfumeEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaPerfumeEmbeddingClient.class);
    private final RestClient client;

    public OllamaPerfumeEmbeddingClient(RestClient client) { this.client = client; }

    @Override
    public void verifyModel(EmbeddingModelIdentity model) {
        var response = request("/api/tags", client.get().uri("/api/tags"));
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
        var response = request("/api/embed", client.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model.name(), "input", List.of(text), "truncate", false,
                        "dimensions", EmbeddingModelIdentity.DIMENSIONS, "keep_alive", "5m")));
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

    private static JsonNode request(String endpoint, RestClient.RequestHeadersSpec<?> request) {
        long started = System.nanoTime();
        int[] httpStatus = {0}; // Zero means no response status was received.
        try {
            return request.retrieve().onStatus(status -> {
                httpStatus[0] = status.value();
                return status.isError();
            }, (sent, response) -> {
                // Preserve HTTP 400/429/5xx without parsing or retaining a potentially sensitive error body.
                throw new EmbeddingFailure(EmbeddingFailure.Code.HTTP_ERROR);
            }).body(JsonNode.class);
        } catch (EmbeddingFailure known) {
            throw failed(endpoint, httpStatus[0], started, known.code(), known);
        } catch (RestClientResponseException status) {
            throw failed(endpoint, status.getStatusCode().value(), started, EmbeddingFailure.Code.HTTP_ERROR, status);
        } catch (RestClientException failure) {
            throw failed(endpoint, httpStatus[0], started, classify(failure), failure);
        }
    }

    private static EmbeddingFailure.Code classify(RestClientException failure) {
        boolean transport = failure instanceof ResourceAccessException;
        // Body-read I/O errors are wrapped in a plain RestClientException, not necessarily ResourceAccessException.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return EmbeddingFailure.Code.TIMEOUT;
            }
            // Jackson parse/JSON-EOF errors are also IOExceptions, but do not prove a connection failure.
            if (cause instanceof IOException && !(cause instanceof JsonProcessingException)) { transport = true; }
        }
        return transport ? EmbeddingFailure.Code.CONNECTION_ERROR : EmbeddingFailure.Code.INVALID_RESPONSE;
    }

    private static EmbeddingFailure failed(String endpoint, int httpStatus, long started, EmbeddingFailure.Code code, Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 32; depth++) { root = root.getCause(); }
        log.warn("Ollama embedding request failed: endpoint={}, code={}, httpStatus={}, elapsedMs={}, exceptionType={}, rootCauseType={}",
                endpoint, code, httpStatus, (System.nanoTime() - started) / 1_000_000,
                failure.getClass().getSimpleName(), root.getClass().getSimpleName());
        // No exception messages, stack traces, headers, input text, vectors or response bodies escape this boundary.
        return new EmbeddingFailure(code);
    }

    private static void invalid() { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_RESPONSE); }
}
