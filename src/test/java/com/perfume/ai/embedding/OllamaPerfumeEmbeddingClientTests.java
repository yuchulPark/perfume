package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.SocketTimeoutException;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** HTTP is completely intercepted; not even localhost or a live Ollama is contacted. */
class OllamaPerfumeEmbeddingClientTests {
    static final EmbeddingModelIdentity MODEL = new EmbeddingModelIdentity("qwen3-embedding:0.6b", "a".repeat(64), EmbeddingModelIdentity.TEXT_VERSION);
    private MockRestServiceServer server;
    private OllamaPerfumeEmbeddingClient client;

    @BeforeEach
    void prepare() {
        var builder = RestClient.builder().baseUrl("http://ollama:11434");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OllamaPerfumeEmbeddingClient(builder.build());
    }

    @AfterEach void noUnexpectedRequestsOrRetries() { server.verify(); }

    @Test
    void requestsExactModelAndDimensionsWithoutTruncationOrCredentials() {
        tags("a".repeat(64));
        server.expect(requestTo("http://ollama:11434/api/embed")).andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("""
                        {"model":"qwen3-embedding:0.6b", "input":["stored text"], "dimensions":1024,
                         "truncate":false, "keep_alive":"5m"}
                        """))
                .andRespond(withSuccess(response(1024, "0.25"), MediaType.APPLICATION_JSON));
        tags("a".repeat(64));
        assertThat(client.embed(MODEL, "stored text")).hasSize(1024).containsOnly(0.25f);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1023, 1025})
    void rejectsDimensionMismatch(int dimensions) {
        tags("a".repeat(64));
        embedResponse(response(dimensions, "1"));
        assertFailure(() -> client.embed(MODEL, "stored text"), EmbeddingFailure.Code.INVALID_VECTOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"1\"", "1e100", "1e400", "0", "\"NaN\"", "\"Infinity\""})
    void rejectsInvalidNumbersOverflowAndZeroVectors(String value) {
        tags("a".repeat(64));
        embedResponse(response(1024, value));
        assertFailure(() -> client.embed(MODEL, "stored text"), EmbeddingFailure.Code.INVALID_VECTOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"model\":\"wrong\",\"embeddings\":[[1]]}",
            "{\"model\":\"qwen3-embedding:0.6b\",\"embeddings\":[]}",
            "{\"model\":\"qwen3-embedding:0.6b\",\"embeddings\":[[1],[2]]}", "{\"error\":\"private-text\"}"})
    void rejectsErrorAndMalformedResponseStructures(String json) {
        tags("a".repeat(64));
        embedResponse(json);
        assertFailure(() -> client.embed(MODEL, "stored text"), EmbeddingFailure.Code.INVALID_RESPONSE);
    }

    @Test
    void aRetaggedModelIsRejectedBeforeAndAfterInference() {
        tags("b".repeat(64));
        tags("a".repeat(64));
        embedResponse(response(1024, "1"));
        tags("b".repeat(64));
        assertFailure(() -> client.embed(MODEL, "stored text"), EmbeddingFailure.Code.MODEL_MISMATCH);
        assertFailure(() -> client.embed(MODEL, "stored text"), EmbeddingFailure.Code.MODEL_MISMATCH);
    }

    @Test
    void missingModelDoesNotTriggerADownload() {
        server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withSuccess("{\"models\":[]}", MediaType.APPLICATION_JSON));
        assertFailure(() -> client.verifyModel(MODEL), EmbeddingFailure.Code.MODEL_MISMATCH);
    }

    @Test
    void httpFailureAndTimeoutAreSafeAndHaveNoAutomaticRetry() {
        tags("a".repeat(64));
        server.expect(requestTo("http://ollama:11434/api/embed")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("private-key-and-response-text").contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withException(new SocketTimeoutException("private-timeout-text")));
        assertFailure(() -> client.embed(MODEL, "stored text"), EmbeddingFailure.Code.HTTP_ERROR);
        assertFailure(() -> client.verifyModel(MODEL), EmbeddingFailure.Code.TIMEOUT);
    }

    private void tags(String digest) {
        server.expect(requestTo("http://ollama:11434/api/tags")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"models\":[{\"name\":\"qwen3-embedding:0.6b\",\"digest\":\"" + digest + "\"}]}", MediaType.APPLICATION_JSON));
    }
    private void embedResponse(String json) {
        server.expect(requestTo("http://ollama:11434/api/embed")).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }
    private static String response(int dimensions, String value) {
        return "{\"model\":\"qwen3-embedding:0.6b\",\"embeddings\":[[" + String.join(",", Collections.nCopies(dimensions, value)) + "]]}";
    }
    private static void assertFailure(Runnable operation, EmbeddingFailure.Code code) {
        var failure = catchThrowableOfType(EmbeddingFailure.class, operation::run);
        assertThat(failure).isNotNull().hasNoCause().hasMessageNotContaining("private");
        assertThat(failure.code()).isEqualTo(code);
    }
}
