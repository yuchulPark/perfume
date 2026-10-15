package com.perfume.ai.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.Collections;
import com.perfume.ai.embedding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaQueryEmbeddingAdapterTests {
    static final EmbeddingModelIdentity MODEL = new EmbeddingModelIdentity("qwen3-embedding:0.6b", "a".repeat(64), "perfume-v1");
    @Test void reusesExactBatchPayloadAndFullDigestVerificationBeforeAndAfterEmbedding() {
        var builder = RestClient.builder().baseUrl("http://ollama:11434"); var server = MockRestServiceServer.bindTo(builder).build();
        tags(server, "a");
        server.expect(requestTo("http://ollama:11434/api/embed")).andExpect(content().json("""
                {"model":"qwen3-embedding:0.6b","input":["여름 향수"],"dimensions":1024,"truncate":false,"keep_alive":"5m"}
                """)).andRespond(withSuccess("{\"model\":\"qwen3-embedding:0.6b\",\"embeddings\":[[" + String.join(",", Collections.nCopies(1024, "1")) + "]]}", MediaType.APPLICATION_JSON));
        tags(server, "a");
        var adapter = new OllamaQueryEmbeddingAdapter(new OllamaPerfumeEmbeddingClient(builder.build()), MODEL);
        assertThat(adapter.embed("여름 향수")).hasSize(1024).containsOnly(1f); server.verify();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsRetaggedModelsBeforeOrAfterInferenceWithoutDownloading(boolean after) {
        var builder = RestClient.builder().baseUrl("http://ollama:11434"); var server = MockRestServiceServer.bindTo(builder).build();
        tags(server, after ? "a" : "b");
        if (after) {
            server.expect(requestTo("http://ollama:11434/api/embed")).andRespond(withSuccess("{\"model\":\"qwen3-embedding:0.6b\",\"embeddings\":[[" + String.join(",", Collections.nCopies(1024, "1")) + "]]}", MediaType.APPLICATION_JSON));
            tags(server, "b");
        }
        var adapter = new OllamaQueryEmbeddingAdapter(new OllamaPerfumeEmbeddingClient(builder.build()), MODEL);
        assertThat(catchThrowableOfType(SemanticSearchFailure.class, () -> adapter.embed("private-query")).code()).isEqualTo(SemanticSearchFailure.Code.MODEL_MISMATCH);
        server.verify();
    }
    @ParameterizedTest @CsvSource({"TIMEOUT,OLLAMA_TIMEOUT", "CONNECTION_ERROR,OLLAMA_UNAVAILABLE", "MODEL_MISMATCH,MODEL_MISMATCH",
            "INVALID_VECTOR,INVALID_EMBEDDING", "INVALID_RESPONSE,OLLAMA_INVALID_RESPONSE", "HTTP_ERROR,OLLAMA_HTTP_ERROR"})
    void mapsExistingFailuresWithoutKeepingTheUnderlyingCause(EmbeddingFailure.Code input, SemanticSearchFailure.Code expected) {
        var client = mock(PerfumeEmbeddingClient.class); when(client.embed(any(), anyString())).thenThrow(new EmbeddingFailure(input));
        var failure = catchThrowableOfType(SemanticSearchFailure.class, () -> new OllamaQueryEmbeddingAdapter(client, MODEL).embed("private-query"));
        assertThat(failure.code()).isEqualTo(expected); assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
        verify(client).embed(MODEL, "private-query"); verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings = {"dimension", "zero", "nan", "infinity"})
    void rejectsInvalidVectorsEvenIfAnAdapterClientReturnsThem(String kind) {
        var vector = vector();
        switch (kind) { case "dimension" -> vector = new float[1023]; case "zero" -> vector[0] = 0; case "nan" -> vector[0] = Float.NaN; case "infinity" -> vector[0] = Float.POSITIVE_INFINITY; }
        var client = mock(PerfumeEmbeddingClient.class); when(client.embed(any(), anyString())).thenReturn(vector);
        assertThat(catchThrowableOfType(SemanticSearchFailure.class, () -> new OllamaQueryEmbeddingAdapter(client, MODEL).embed("query")).code()).isEqualTo(SemanticSearchFailure.Code.INVALID_EMBEDDING);
    }
    @Test void actualHttpErrorBodyIsNotExposedAndNoRetryIsMade() {
        var builder = RestClient.builder().baseUrl("http://ollama:11434"); var server = MockRestServiceServer.bindTo(builder).build(); tags(server, "a");
        server.expect(requestTo("http://ollama:11434/api/embed")).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("private-response-query-vector"));
        var adapter = new OllamaQueryEmbeddingAdapter(new OllamaPerfumeEmbeddingClient(builder.build()), MODEL);
        var failure = catchThrowableOfType(SemanticSearchFailure.class, () -> adapter.embed("private-query"));
        assertThat(failure.code()).isEqualTo(SemanticSearchFailure.Code.OLLAMA_HTTP_ERROR); assertThat(failure).hasNoCause().hasMessageNotContaining("private-"); server.verify();
    }
    private static void tags(MockRestServiceServer server, String digit) {
        server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withSuccess("{\"models\":[{\"name\":\"qwen3-embedding:0.6b\",\"digest\":\"" + digit.repeat(64) + "\"}]}", MediaType.APPLICATION_JSON));
    }
    static float[] vector() { var vector = new float[1024]; vector[0] = 1; return vector; }
}
