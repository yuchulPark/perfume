package com.perfume.scentrev.client;

import static com.perfume.scentrev.client.ScentRevMcpClientException.FailureType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.config.ScentRevClientProperties;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpTransportException;

/** Offline error-contract tests: SDK mocked; no import, database, or live quota consumption. */
class ScentRevMcpClientLimitsTests {
    private static final String KEY = "fake-limit-test-key";
    private static final String SCHEMA_ERROR = "Output validation error: Additional properties are not allowed "
            + "('quota', 'retry_after_ms' were unexpected)";
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpSyncClient sdk = mock(McpSyncClient.class);
    private ScentRevMcpClient client;

    @BeforeEach
    void setUp() {
        var properties = new ScentRevClientProperties();
        properties.setApiKey(KEY);
        when(sdk.initialize()).thenReturn(mock(InitializeResult.class));
        client = new ScentRevMcpClient(properties, mapper, () -> sdk);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void distinguishesExplicitLimitsAndRejectsEvenAnApparentlySuccessfulPage(boolean isError) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), isError, Map.of(
                "results", List.of(), "truncated", false, "offset", 0, "limit", 10,
                "error", Map.of("code", "rate_limit", "message", "Request limited " + KEY, "retry_after_ms", 1250),
                "quota", Map.of("remaining", 0), "retry_after_ms", 9999), null));

        var error = failure(RATE_LIMIT);

        assertThat(error.getRetryAfterMs()).isEqualTo(1250L); // Explicit error hint takes precedence.
        assertThat(error.getStatusCode()).isNull(); // A provider code is not an observed HTTP status.
        assertThat(error.getMessage()).contains("1250 ms", "No automatic retry");
        oneRequestOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rate_limit", "rate_limited", "rate_limit_exceeded", "too_many_requests", "quota_exceeded", "quota_exhausted"})
    void acceptsCanonicalFlatLimitCodesAndTopLevelRetryHint(String code) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("error", code, "message", "Provider request restriction", "retry_after_ms", 2000), null));

        assertThat(failure(RATE_LIMIT).getRetryAfterMs()).isEqualTo(2000L);
        oneRequestOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "1e3", "true", "null", "\"1000\"", "\"not-a-delay\"",
            "9223372036854775808", "{}", "[]"})
    void ignoresInvalidRetryHintsWithoutLosingTheLimitClassification(String value) throws Exception {
        JsonNode payload = mapper.readTree("{\"error\":{\"code\":\"quota_exceeded\",\"retry_after_ms\":" + value + "}}");
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, payload, null));

        assertThat(failure(RATE_LIMIT).getRetryAfterMs()).isNull();
        oneRequestOnly();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 9223372036854775807L})
    void acceptsOnlyNonnegativeIntegralLongMilliseconds(long value) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("error", Map.of("code", "quota_exceeded", "retry_after_ms", value)), null));

        assertThat(failure(RATE_LIMIT).getRetryAfterMs()).isEqualTo(value);
        oneRequestOnly();
    }

    @Test
    void invalidNestedHintDoesNotFallBackToConflictingTopLevelHint() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, Map.of(
                "error", Map.of("code", "rate_limit", "retry_after_ms", -1), "retry_after_ms", 5000), null));

        assertThat(failure(RATE_LIMIT).getRetryAfterMs()).isNull();
        oneRequestOnly();
    }

    @Test
    void textualJsonRestrictionWithoutErrorFlagOrResultsIsStillRejected() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(
                "{\"code\":\"rate_limit\",\"message\":\"Limited\",\"retry_after_ms\":500}")), false, null, null));

        assertThat(failure(RATE_LIMIT).getRetryAfterMs()).isEqualTo(500L);
        oneRequestOnly();
    }

    @Test
    void serverValidationMessageIsDistinctAndDoesNotInventQuotaStatusOrDelay() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(SCHEMA_ERROR)), true, null, null));

        var error = failure(SERVER_OUTPUT_SCHEMA);

        assertThat(error.getRetryAfterMs()).isNull();
        assertThat(error.getStatusCode()).isNull();
        assertThat(error.getMessage()).contains("server outputSchema", "provider must fix");
        oneRequestOnly();
    }

    @Test
    void nestedJsonRpcQuotaErrorRetainsOnlyItsValidatedRetryHint() {
        var rpcError = McpError.builder(-32000).message("Tool failed " + KEY).data(Map.of(
                "error", Map.of("code", "quota_exceeded", "retry_after_ms", 3000), "private", KEY)).build();
        when(sdk.callTool(any())).thenThrow(new IllegalStateException("wrapper", rpcError));

        assertThat(failure(RATE_LIMIT).getRetryAfterMs()).isEqualTo(3000L);
        oneRequestOnly();
    }

    @Test
    void protocolValidationErrorCannotBeRepairedByClientAndIsNotAssumedToBeARateLimit() {
        when(sdk.callTool(any())).thenThrow(McpError.builder(-32603).message(SCHEMA_ERROR).build());

        assertThat(failure(SERVER_OUTPUT_SCHEMA).getRetryAfterMs()).isNull();
        oneRequestOnly();
    }

    @Test
    void sdkHttp429IsDistinguishedWithoutInventingLostRetryHeaders() {
        when(sdk.callTool(any())).thenThrow(new McpTransportException("Invalid request. Status code: 429"));

        var error = failure(RATE_LIMIT);

        assertThat(error.getStatusCode()).isEqualTo(429);
        assertThat(error.getRetryAfterMs()).isNull();
        oneRequestOnly();
    }

    @Test
    void mentioningQuotaFieldsDoesNotTurnAnUnrelatedErrorIntoARateLimit() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), true, Map.of(
                "error", Map.of("code", "invalid_param", "message", "Unknown fields quota and retry_after_ms"),
                "quota", Map.of("remaining", 0), "retry_after_ms", 1000), null));

        assertThat(failure(TOOL_CALL).getRetryAfterMs()).isNull();
        oneRequestOnly();
    }

    @Test
    void quotaMetadataAloneDoesNotInvalidateAnOtherwiseNormalPage() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, Map.of(
                "results", List.of(Map.of("fragrance_slug", "creed-aventus", "public_id", "id-aventus", "name", "Aventus")),
                "truncated", false, "offset", 0, "limit", 10, "total_returned", 1,
                "quota", Map.of("remaining", 0), "retry_after_ms", 1000), null));

        assertThat(client.searchFragrancesFiltered("creed", 0).results()).hasSize(1);
        oneRequestOnly();
    }

    @Test
    void profileRestrictionCannotBeConvertedToAProfile() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("error", Map.of("code", "quota_exceeded", "retry_after_ms", 500)), null));

        assertThatThrownBy(() -> client.getFragranceProfile("creed-aventus"))
                .isInstanceOf(ScentRevMcpClientException.class).satisfies(error -> {
                    assertThat(((ScentRevMcpClientException) error).getFailureType()).isEqualTo(RATE_LIMIT);
                    assertThat(((ScentRevMcpClientException) error).getRetryAfterMs()).isEqualTo(500L);
                });
        oneRequestOnly();
    }

    private ScentRevMcpClientException failure(ScentRevMcpClientException.FailureType type) {
        var error = catchThrowableOfType(() -> client.searchFragrancesFiltered("creed", 0), ScentRevMcpClientException.class);
        assertThat(error).isNotNull().hasNoCause().hasMessageNotContaining(KEY);
        assertThat(error.getFailureType()).isEqualTo(type);
        return error;
    }

    private void oneRequestOnly() {
        verify(sdk).initialize();
        verify(sdk).callTool(any());
        verifyNoMoreInteractions(sdk);
    }
}
