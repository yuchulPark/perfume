package com.perfume.ai.recommend;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.perfume.ai.transport.RecommendationCancellation;

/** Non-streaming local chat only. No tools, generation retries, pull, persistence or chat history. */
public class OllamaRecommendationClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaRecommendationClient.class);
    private static final int MAX_RESPONSE_BYTES = 65536;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final PerfumeRecommendationOptions options;
    public OllamaRecommendationClient(RestClient client, ObjectMapper mapper, PerfumeRecommendationOptions options) {
        this.client = client; this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION); this.options = options;
    }
    private record ChatRequest(String model, List<Map<String, String>> messages, boolean stream, String format,
            boolean think, boolean truncate, String keep_alive, Map<String, Object> options) {
        @Override public String toString() { return "OllamaRecommendationRequest[stream=false]"; }
    }
    public String generate(RecommendationPromptBuilder.Prompt prompt) {
        OllamaRecommendationDiagnostics.started(prompt, options);
        verifyModel();
        checkInterrupted();
        var response = request("chat", client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON).body(new ChatRequest(
                options.model(), prompt.messages(), false, "json", false, false, "5m", Map.of("num_ctx", PerfumeRecommendationOptions.CONTEXT_TOKENS,
                        "num_predict", options.maxOutputTokens(), "temperature", options.temperature(), "num_thread", 3))));
        var message = response.path("message");
        if (!options.model().equals(response.path("model").asText()) || !response.path("done").isBoolean() || !response.path("done").booleanValue()
                || !"assistant".equals(message.path("role").asText()) || !message.path("content").isTextual()
                || message.path("content").textValue().isBlank() || message.path("content").textValue().length() > 8192
                || (message.has("tool_calls") && !message.path("tool_calls").isEmpty() && !message.path("tool_calls").isNull())
                || (message.hasNonNull("thinking") && !message.path("thinking").asText().isBlank())
                || (response.has("done_reason") && !"stop".equals(response.path("done_reason").asText()))) { throw invalid(); }
        validateCount(response, "prompt_eval_count", PerfumeRecommendationOptions.CONTEXT_TOKENS - options.maxOutputTokens());
        validateCount(response, "eval_count", options.maxOutputTokens());
        checkInterrupted(); verifyModel();
        return message.path("content").textValue();
    }
    private void verifyModel() {
        checkInterrupted();
        var response = request("tags", client.get().uri("/api/tags"));
        if (!response.path("models").isArray()) { throw invalid(); }
        int matches = 0;
        for (var installed : response.path("models")) {
            if (options.model().equals(installed.path("name").asText()) || options.model().equals(installed.path("model").asText())) {
                matches++;
                try {
                    if (!installed.path("digest").isTextual() || !options.modelDigest().equals(EmbeddingModelIdentity.normalizeDigest(installed.path("digest").textValue()))) {
                        throw mismatch();
                    }
                } catch (IllegalArgumentException malformed) { throw mismatch(); }
            }
        }
        if (matches != 1) { throw mismatch(); }
    }
    private JsonNode request(String stage, RestClient.RequestHeadersSpec<?> request) {
        long started = System.nanoTime();
        int[] httpStatus = {0};
        boolean[] responseComplete = {false};
        try {
            checkInterrupted();
            return request.exchange((sent, response) -> {
                httpStatus[0] = response.getStatusCode().value();
                if (!response.getStatusCode().is2xxSuccessful()) { throw new RecommendationFailure(RecommendationFailure.Code.LLM_HTTP_ERROR); }
                var bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) { throw invalid(); }
                responseComplete[0] = true;
                JsonNode json;
                try { json = mapper.readTree(bytes); }
                catch (JsonProcessingException invalidJson) { throw invalid(); }
                if (json == null || !json.isObject() || json.has("error")) { throw invalid(); }
                if ("chat".equals(stage)) { OllamaRecommendationDiagnostics.received(json, (System.nanoTime() - started) / 1_000_000, httpStatus[0]); }
                return json;
            });
        } catch (RecommendationFailure known) {
            failed(stage, started, known.code(), httpStatus[0], responseComplete[0]); throw known;
        } catch (RestClientException failure) {
            var code = classify(failure); failed(stage, started, code, httpStatus[0], responseComplete[0]); throw new RecommendationFailure(code);
        }
    }
    private static RecommendationFailure.Code classify(RestClientException failure) {
        boolean transport = failure instanceof ResourceAccessException;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) { return RecommendationFailure.Code.LLM_TIMEOUT; }
            if (cause instanceof IOException && !(cause instanceof JsonProcessingException)) { transport = true; }
        }
        return transport ? RecommendationFailure.Code.LLM_UNAVAILABLE : RecommendationFailure.Code.LLM_INVALID_RESPONSE;
    }
    private static void validateCount(JsonNode response, String field, int limit) {
        if (response.has(field) && (!response.path(field).isIntegralNumber() || !response.path(field).canConvertToInt()
                || response.path(field).intValue() < 0 || response.path(field).intValue() > limit)) { throw invalid(); }
    }
    static void checkInterrupted() {
        var scope = RecommendationCancellation.current();
        if (Thread.currentThread().isInterrupted() || (scope != null && scope.isCancelled())) { throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_INTERRUPTED); }
    }
    private static void failed(String stage, long started, RecommendationFailure.Code code, int httpStatus, boolean responseComplete) {
        log.warn("Ollama recommendation failed: stage={}, code={}, elapsedMs={}, httpStatus={}, responseComplete={}, serverMetricsAvailable=false",
                stage, code, (System.nanoTime() - started) / 1_000_000, httpStatus, responseComplete);
    }
    private static RecommendationFailure invalid() { return new RecommendationFailure(RecommendationFailure.Code.LLM_INVALID_RESPONSE); }
    private static RecommendationFailure mismatch() { return new RecommendationFailure(RecommendationFailure.Code.MODEL_MISMATCH); }
}
