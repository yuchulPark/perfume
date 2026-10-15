package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaRecommendationDiagnosticsTests {
    @Test void recordsOnlyPromptSizesAndExistingLimitsWithoutSerializingSensitiveMessages() {
        var privateQuery = "private-question-secret";
        var prompt = builder().build(privateQuery, List.of(evidence(1)));
        try (var logs = new Capture(OllamaRecommendationDiagnostics.class)) {
            OllamaRecommendationDiagnostics.started(prompt, options());
            var message = logs.messages().get(0);
            assertThat(message).contains("event=request", "messageCount=3", "messageContentBytes=" + prompt.inputBytes(),
                    "queryMessageBytes=", "evidenceMessageBytes=", "contextSources=1", "numCtx=4096", "maxOutputTokens=512",
                    "numThreads=3", "stream=false", "format=json", "think=false", "truncate=false", "readTimeoutMs=180000", "generationTimeoutMs=300000")
                    .doesNotContain(privateQuery, "Bergamot", "Stored", "sha256:", "http://");
        }
    }
    @Test void logsActualNanosecondsAndSeparatePromptAndGenerationRates() throws Exception {
        var response = response();
        try (var logs = new Capture(OllamaRecommendationDiagnostics.class)) {
            OllamaRecommendationDiagnostics.received(response, 277000, 200);
            assertThat(logs.messages().get(0)).contains("responseComplete=true", "httpStatus=200", "elapsedMs=277000", "done=true", "doneReason=stop",
                    "serverMetricsAvailable=true", "total_duration_ns=277000000000", "load_duration_ns=17000000000",
                    "prompt_eval_duration_ns=10000000000", "eval_duration_ns=250000000000", "prompt_eval_count=1000", "eval_count=500",
                    "promptTokensPerSecond=100.000", "generatedTokensPerSecond=2.000", "contentChars=", "thinkingChars=0")
                    .doesNotContain("private-", "question", "http://", "sha256:");
            assertThat(logs.appender.list).allMatch(event -> event.getThrowableProxy() == null);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"negative", "string", "overflow", "decimal", "null", "boolean"})
    void untrustedOrMalformedNumericMetadataNeverGetsPrintedOrChangesTheResponsePolicy(String kind) throws Exception {
        var response = response();
        switch (kind) {
            case "negative" -> response.put("load_duration", -1); case "string" -> response.put("load_duration", "private-password");
            case "overflow" -> response.put("load_duration", new java.math.BigInteger("999999999999999999999999"));
            case "decimal" -> response.put("load_duration", 1.5); case "null" -> response.putNull("load_duration"); case "boolean" -> response.put("load_duration", true);
        }
        response.put("done_reason", "private-prompt-answer");
        try (var logs = new Capture(OllamaRecommendationDiagnostics.class)) {
            OllamaRecommendationDiagnostics.received(response, 1, 200);
            assertThat(logs.messages().get(0)).contains("load_duration_ns=unavailable", "serverMetricsAvailable=false", "doneReason=unavailable")
                    .doesNotContain("private-", "999999999999999999999999");
        }
    }
    @Test void missingMetadataIsUnavailableAndZeroDurationNeverCreatesInfiniteRates() {
        try (var logs = new Capture(OllamaRecommendationDiagnostics.class)) {
            OllamaRecommendationDiagnostics.received(MAPPER.createObjectNode(), 1, 200);
            var zero = MAPPER.createObjectNode();
            for (var field : List.of("load_duration", "prompt_eval_duration", "eval_duration", "prompt_eval_count", "eval_count")) { zero.put(field, 0); }
            OllamaRecommendationDiagnostics.received(zero, 1, 200);
            assertThat(logs.messages().get(0)).contains("serverMetricsAvailable=false", "eval_count=unavailable", "generatedTokensPerSecond=unavailable");
            assertThat(logs.messages().get(1)).contains("serverMetricsAvailable=true", "eval_count=0", "generatedTokensPerSecond=unavailable", "promptTokensPerSecond=unavailable")
                    .doesNotContain("Infinity", "NaN");
        }
    }
    @Test void successfulChatUsesServerMetricsWithoutChangingPayloadOrTheReturnedAnswer() throws Exception {
        var fixture = new Fixture(); tags(fixture.server); var response = response();
        fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(content().json("""
                {"stream":false,"format":"json","think":false,"truncate":false,"options":{"num_ctx":4096,"num_predict":512,"num_thread":3,"temperature":0.2}}
                """)).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        tags(fixture.server);
        try (var logs = new Capture(OllamaRecommendationDiagnostics.class)) {
            assertThat(fixture.client.generate(prompt())).isEqualTo("private-generated-answer");
            assertThat(logs.messages()).hasSize(2); assertThat(String.join("\n", logs.messages())).doesNotContain("private-");
            assertThat(logs.messages().get(1)).contains("event=response", "generatedTokensPerSecond=2.000");
        }
        fixture.server.verify();
    }
    @Test void tokenLimitCompletionIsMeasuredBeforeItIsRejectedByExistingValidation() throws Exception {
        var fixture = new Fixture(); tags(fixture.server); var response = response(); response.put("done_reason", "length"); response.put("eval_count", 512);
        fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        try (var logs = new Capture(OllamaRecommendationDiagnostics.class)) {
            assertThat(catchThrowableOfType(RecommendationFailure.class, () -> fixture.client.generate(prompt())).code()).isEqualTo(RecommendationFailure.Code.LLM_INVALID_RESPONSE);
            assertThat(logs.messages().get(1)).contains("doneReason=length", "eval_count=512", "serverMetricsAvailable=true");
        }
        fixture.server.verify();
    }
    @Test void bodyTimeoutAfterHttp200DoesNotInventFinalMetricsOrPrintPartialContent() {
        var fixture = new Fixture(); tags(fixture.server);
        fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(request -> new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
            @Override public InputStream getBody() { return new InputStream() {
                @Override public int read() throws SocketTimeoutException { throw new SocketTimeoutException("private-answer-password"); }
            }; }
        });
        try (var diagnostics = new Capture(OllamaRecommendationDiagnostics.class); var failures = new Capture(OllamaRecommendationClient.class)) {
            assertThat(catchThrowableOfType(RecommendationFailure.class, () -> fixture.client.generate(prompt())).code()).isEqualTo(RecommendationFailure.Code.LLM_TIMEOUT);
            assertThat(diagnostics.messages()).hasSize(1); assertThat(diagnostics.messages().get(0)).contains("event=request");
            assertThat(failures.messages()).hasSize(1);
            assertThat(failures.messages().get(0)).contains("LLM_TIMEOUT", "httpStatus=200", "responseComplete=false", "serverMetricsAvailable=false").doesNotContain("private-");
        }
        fixture.server.verify();
    }
    private static ObjectNode response() throws Exception {
        return (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsString(Map.of("model", "qwen3:4b-instruct", "done", true, "done_reason", "stop",
                "message", Map.of("role", "assistant", "content", "private-generated-answer"), "total_duration", 277000000000L, "load_duration", 17000000000L,
                "prompt_eval_duration", 10000000000L, "eval_duration", 250000000000L, "prompt_eval_count", 1000, "eval_count", 500)));
    }
    private static void tags(MockRestServiceServer server) {
        server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withSuccess("{\"models\":[{\"name\":\"qwen3:4b-instruct\",\"digest\":\"" + "b".repeat(64) + "\"}]}", MediaType.APPLICATION_JSON));
    }
    private static class Fixture {
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama:11434");
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final OllamaRecommendationClient client = new OllamaRecommendationClient(builder.build(), MAPPER, options());
    }
    private static class Capture implements AutoCloseable {
        final Logger logger;
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Capture(Class<?> type) { logger = (Logger) LoggerFactory.getLogger(type); appender.start(); logger.addAppender(appender); }
        List<String> messages() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }
        @Override public void close() { logger.detachAppender(appender); appender.stop(); }
    }
}
