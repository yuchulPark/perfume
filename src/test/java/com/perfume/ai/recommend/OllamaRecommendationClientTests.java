package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Map;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaRecommendationClientTests {
    @Test void usesNonStreamingChatWithSeparateMessagesCpuLimitsAndTwoFullDigestChecks() throws Exception {
        var fixture = new Fixture(); tags(fixture.server, "b"); var prompt = prompt(); var generated = generated(prompt);
        fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(method(HttpMethod.POST)).andExpect(request -> {
            var json = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(json.path("model").textValue()).isEqualTo("qwen3:4b-instruct");
            assertThat(json.path("stream").booleanValue()).isFalse(); assertThat(json.path("think").booleanValue()).isFalse();
            assertThat(json.path("truncate").booleanValue()).isFalse(); assertThat(json.path("format").asText()).isEqualTo("json");
            assertThat(json.path("options").path("num_ctx").intValue()).isEqualTo(4096);
            assertThat(json.path("options").path("num_predict").intValue()).isEqualTo(128);
            assertThat(json.path("options").path("temperature").doubleValue()).isEqualTo(0.2);
            assertThat(json.path("options").path("num_thread").intValue()).isEqualTo(3);
            assertThat(json.path("messages")).hasSize(3); assertThat(json.has("tools")).isFalse(); assertThat(json.has("context")).isFalse();
        }).andRespond(withSuccess(chat(generated), MediaType.APPLICATION_JSON));
        tags(fixture.server, "b");
        assertThat(fixture.client.generate(prompt)).isEqualTo(generated); fixture.server.verify();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsRetaggedLlmBeforeAndAfterChatWithoutRetryingOrPulling(boolean after) throws Exception {
        var fixture = new Fixture(); tags(fixture.server, after ? "b" : "a");
        if (after) {
            fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(withSuccess(chat(generated(prompt())), MediaType.APPLICATION_JSON));
            tags(fixture.server, "c");
        }
        assertCode(fixture, RecommendationFailure.Code.MODEL_MISMATCH); fixture.server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "short", "duplicate"})
    void requiresExactlyOneInstalledModelWithAFullDigest(String kind) {
        var fixture = new Fixture();
        String item = "{\"name\":\"qwen3:4b-instruct\",\"digest\":\"" + (kind.equals("short") ? "short" : "b".repeat(64)) + "\"}";
        String models = kind.equals("missing") ? "[]" : "[" + item + (kind.equals("duplicate") ? "," + item : "") + "]";
        fixture.server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withSuccess("{\"models\":" + models + "}", MediaType.APPLICATION_JSON));
        assertCode(fixture, RecommendationFailure.Code.MODEL_MISMATCH); fixture.server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"empty", "blank", "wrong-model", "incomplete", "role", "tool", "thinking", "length", "prompt-overflow", "output-overflow", "bad-count", "error", "json", "extra-json", "oversize"})
    void rejectsMalformedTruncatedOrUnboundedResponses(String kind) throws Exception {
        var fixture = new Fixture(); tags(fixture.server, "b"); var json = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(chat(generated(prompt())));
        var message = (com.fasterxml.jackson.databind.node.ObjectNode) json.path("message");
        switch (kind) {
            case "empty" -> message.remove("content"); case "blank" -> message.put("content", "  ");
            case "wrong-model" -> json.put("model", "other-model"); case "incomplete" -> json.put("done", false);
            case "role" -> message.put("role", "user"); case "tool" -> message.putArray("tool_calls").addObject().put("name", "do-something");
            case "thinking" -> message.put("thinking", "private-thoughts"); case "length" -> json.put("done_reason", "length");
            case "prompt-overflow" -> json.put("prompt_eval_count", 4096); case "output-overflow" -> json.put("eval_count", 129);
            case "bad-count" -> json.put("eval_count", "1"); case "error" -> json.put("error", "private-response");
        }
        String body = switch (kind) { case "json" -> "{\"private-response\":"; case "extra-json" -> json + " {}"; case "oversize" -> " ".repeat(65537); default -> json.toString(); };
        fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertCode(fixture, RecommendationFailure.Code.LLM_INVALID_RESPONSE); fixture.server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {400, 429, 500})
    void httpErrorsRemainErrorsWithoutBodyLoggingOrRetries(int status) {
        var logger = (Logger) LoggerFactory.getLogger(OllamaRecommendationClient.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var fixture = new Fixture(); tags(fixture.server, "b");
            fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(withStatus(HttpStatus.valueOf(status)).body("private-password-api-key-prompt-answer-vector"));
            assertCode(fixture, RecommendationFailure.Code.LLM_HTTP_ERROR); fixture.server.verify();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("stage=chat", "LLM_HTTP_ERROR", "elapsedMs=").doesNotContain("private-", "http://", "Stored", "여름");
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void transportAndTimeoutFailuresAreDistinctIncludingWhileReadingTheBody(boolean timeout) {
        var fixture = new Fixture(); tags(fixture.server, "b");
        fixture.server.expect(requestTo("http://ollama:11434/api/chat")).andRespond(request -> new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
            @Override public InputStream getBody() { return new InputStream() {
                @Override public int read() throws IOException { if (timeout) { throw new SocketTimeoutException("private-body"); } throw new IOException("private-body"); }
            }; }
        });
        assertCode(fixture, timeout ? RecommendationFailure.Code.LLM_TIMEOUT : RecommendationFailure.Code.LLM_UNAVAILABLE); fixture.server.verify();
    }
    @Test void initialTagsConnectionFailureDoesNotAttemptGeneration() {
        var fixture = new Fixture(); fixture.server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withException(new IOException("private-connection")));
        assertCode(fixture, RecommendationFailure.Code.LLM_UNAVAILABLE); fixture.server.verify();
    }
    private static void assertCode(Fixture fixture, RecommendationFailure.Code code) {
        var failure = catchThrowableOfType(RecommendationFailure.class, () -> fixture.client.generate(prompt()));
        assertThat(failure.code()).isEqualTo(code); assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
    }
    private static void tags(MockRestServiceServer server, String digit) {
        server.expect(requestTo("http://ollama:11434/api/tags")).andRespond(withSuccess("{\"models\":[{\"name\":\"qwen3:4b-instruct\",\"digest\":\"" + digit.repeat(64) + "\"}]}", MediaType.APPLICATION_JSON));
    }
    private static String chat(String content) throws Exception {
        return MAPPER.writeValueAsString(Map.of("model", "qwen3:4b-instruct", "done", true, "done_reason", "stop", "message", Map.of("role", "assistant", "content", content), "prompt_eval_count", 1000, "eval_count", 100));
    }
    private static class Fixture {
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama:11434");
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final OllamaRecommendationClient client = new OllamaRecommendationClient(builder.build(), MAPPER, options());
    }
}
