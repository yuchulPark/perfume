package com.perfume.scentrev.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.config.ScentRevClientProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Mocked SDK only: no provider requests, credentials from the environment, or database access. */
class ScentRevMcpClientLoggingTests {
    private static final String KEY = "fake-diagnostic-api-key";
    private static final String PRIVATE_DATA = "private-response-data-must-not-be-logged";
    private final McpSyncClient sdk = mock(McpSyncClient.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ScentRevMcpClient.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;
    private ScentRevMcpClient client;

    @BeforeEach
    void setUp() {
        var properties = new ScentRevClientProperties();
        properties.setApiKey(KEY);
        when(sdk.initialize()).thenReturn(mock(InitializeResult.class));
        client = new ScentRevMcpClient(properties, new ObjectMapper(), () -> sdk);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void discoveryLogsStructuredErrorCodeAndMessageWithoutResponseData(boolean isError) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), isError, Map.of(
                "error", Map.of("code", "rpc_timeout", "message", "Upstream timed out; key " + KEY,
                        "data", Map.of("private", PRIVATE_DATA)), "_ui", PRIVATE_DATA), null));

        assertToolCall(() -> client.searchFragrancesFiltered("creed", 0));

        assertThat(logMessage()).contains("tool=search_fragrances_filtered", "serverCode=rpc_timeout",
                "serverMessage=Upstream timed out; key [REDACTED]");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void profileLogsTextualJsonErrorWithoutPresentationOrRpcData(boolean isError) {
        String json = "{\"error\":{\"code\":-32001,\"message\":\"Plan restriction\",\"data\":\"" + PRIVATE_DATA + "\"}}";
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(
                new TextContent("<html>" + PRIVATE_DATA + "</html>"), new TextContent(json)), isError, null, null));

        assertToolCall(() -> client.getFragranceProfile("creed-aventus"));

        assertThat(logMessage()).contains("tool=get_fragrance_profile", "serverCode=-32001", "serverMessage=Plan restriction");
    }

    @Test
    void sdkFailureReportsNestedProtocolErrorAndExceptionChainWithoutAttachingRawCause() {
        var rpcError = McpError.builder(-32042).message("Provider rejected arguments " + KEY)
                .data(Map.of("private", PRIVATE_DATA)).build();
        when(sdk.callTool(any())).thenThrow(new IllegalStateException("Tool invocation failed", new RuntimeException(rpcError)));

        assertToolCall(() -> client.getFragranceProfile("creed-aventus"));

        assertThat(logMessage()).contains("serverCode=-32042", "serverMessage=Provider rejected arguments [REDACTED]",
                "java.lang.IllegalStateException: Tool invocation failed", "java.lang.RuntimeException: (see nested cause)",
                "io.modelcontextprotocol.spec.McpError: Provider rejected arguments [REDACTED]");
    }

    @Test
    void plainServerMessageRedactsCredentialsAndRemovesLogInjectionCharacters() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(
                "Invalid request " + KEY + " Bearer other-bearer-secret api_key=other-api-secret"
                        + " token='spaced token secret' password=\"spaced password secret\""
                        + " https://user:url-secret@provider.test/mcp?access_token=query-secret\nnext line\u2028end")), true, null, null));

        assertToolCall(() -> client.getFragranceProfile("creed-aventus"));

        assertThat(logMessage()).contains("serverMessage=Invalid request [REDACTED]", "next line")
                .doesNotContain("other-bearer-secret", "other-api-secret", "spaced token secret", "spaced password secret",
                        "url-secret", "query-secret", "\n", "\r", "\u2028");
    }

    @Test
    void nestedMessagesRedactAuthorizationAndCookiesBeforeLogging() {
        when(sdk.callTool(any())).thenThrow(new IllegalStateException("Authorization: Basic other-basic-secret",
                new IllegalArgumentException("Cookie: session=cookie-secret; another=second-cookie-secret")));

        assertToolCall(() -> client.searchFragrancesFiltered("creed", 0));

        assertThat(logMessage()).contains("Authorization: [REDACTED]", "Cookie: [REDACTED]")
                .doesNotContain("other-basic-secret", "cookie-secret", "second-cookie-secret");
    }

    @Test
    void redactsBeforeTruncatingMessageSoNoPartialApiKeyEscapes() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), true, Map.of("error",
                Map.of("code", "server_error", "message", "x".repeat(500) + KEY + " suffix")), null));

        assertToolCall(() -> client.getFragranceProfile("creed-aventus"));

        assertThat((String) appender.list.get(0).getArgumentArray()[2]).hasSize(512)
                .contains("[REDACTED]").doesNotContain("fake-diagnostic");
    }

    @Test
    void incompleteDiagnosticsAndCyclicCausesKeepOriginalFailurePolicy() {
        var first = new IllegalStateException("outer");
        var second = new IllegalArgumentException("inner " + KEY);
        first.initCause(second);
        second.initCause(first);
        when(sdk.callTool(any())).thenThrow(first);

        assertToolCall(() -> client.getFragranceProfile("creed-aventus"));

        assertThat(logMessage()).contains("serverCode=(not supplied)", "serverMessage=(not supplied)",
                "IllegalStateException: outer", "IllegalArgumentException: inner [REDACTED]").hasSizeLessThan(10000);
    }

    @Test
    void successfulProfileProducesNoFailureDiagnostic() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("identity", Map.of("name", "Aventus", "brand_slug", "creed", "fragrance_slug", "creed-aventus")), null));

        client.getFragranceProfile("creed-aventus");

        assertThat(appender.list).isEmpty();
        verify(sdk).callTool(any());
    }

    @Test
    void limitDiagnosticLogsOnlyTheSanitizedMessageAndValidatedRetryHint() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), true, Map.of(
                "error", Map.of("code", "quota_exceeded", "message", "Limit reached " + KEY, "retry_after_ms", 3000),
                "quota", Map.of("private", PRIVATE_DATA)), null));

        assertThatThrownBy(() -> client.searchFragrancesFiltered("creed", 0))
                .isInstanceOf(ScentRevMcpClientException.class).hasNoCause().satisfies(error ->
                        assertThat(((ScentRevMcpClientException) error).getFailureType())
                                .isEqualTo(ScentRevMcpClientException.FailureType.RATE_LIMIT));

        assertThat(appender.list).hasSize(1);
        assertThat(logMessage()).contains("ScentRev MCP RATE_LIMIT", "serverCode=quota_exceeded",
                "serverMessage=Limit reached [REDACTED]", "retryAfterMs=3000").doesNotContain(KEY, PRIVATE_DATA);
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        verify(sdk).callTool(any());
    }

    @Test
    void serverSchemaDiagnosticDoesNotGuessARetryHintFromFieldNames() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(
                "Output validation error: Additional properties are not allowed ('quota', 'retry_after_ms' were unexpected) " + KEY)),
                true, null, null));

        assertThatThrownBy(() -> client.searchFragrancesFiltered("creed", 0))
                .isInstanceOf(ScentRevMcpClientException.class).hasNoCause().satisfies(error -> {
                    assertThat(((ScentRevMcpClientException) error).getFailureType())
                            .isEqualTo(ScentRevMcpClientException.FailureType.SERVER_OUTPUT_SCHEMA);
                    assertThat(((ScentRevMcpClientException) error).getRetryAfterMs()).isNull();
                });

        assertThat(appender.list).hasSize(1);
        assertThat(logMessage()).contains("ScentRev MCP SERVER_OUTPUT_SCHEMA", "quota", "retry_after_ms", "retryAfterMs=null")
                .doesNotContain(KEY, PRIVATE_DATA);
        verify(sdk).callTool(any());
    }

    private void assertToolCall(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ScentRevMcpClientException.class).hasNoCause()
                .hasMessageNotContaining(KEY).satisfies(error ->
                        assertThat(((ScentRevMcpClientException) error).getFailureType())
                                .isEqualTo(ScentRevMcpClientException.FailureType.TOOL_CALL));
        verify(sdk).initialize();
        verify(sdk).callTool(any());
        verifyNoMoreInteractions(sdk);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        assertThat(logMessage()).doesNotContain(KEY, PRIVATE_DATA);
    }

    private String logMessage() { return appender.list.get(0).getFormattedMessage(); }
}
