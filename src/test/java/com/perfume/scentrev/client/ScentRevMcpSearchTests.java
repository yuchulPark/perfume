package com.perfume.scentrev.client;

import static com.perfume.scentrev.client.ScentRevMcpClientException.FailureType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.config.ScentRevClientProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Connected tool metadata contract, with a mocked SDK; no HTTP, PostgreSQL or environment secrets. */
class ScentRevMcpSearchTests {
    private static final String FAKE_KEY = "fake-search-unit-credential";
    private final ScentRevClientProperties properties = new ScentRevClientProperties();
    private final McpSyncClient sdk = mock(McpSyncClient.class);
    private ScentRevMcpClient client;
    @BeforeEach void setUp() {
        properties.setApiKey(FAKE_KEY);
        when(sdk.initialize()).thenReturn(mock(InitializeResult.class));
        client = new ScentRevMcpClient(properties, new ObjectMapper(), () -> sdk);
    }

    @ParameterizedTest @ValueSource(ints = {1, 5, 10})
    void usesExactQueryAndLimitArgumentsAndNeverFollowsTruncatedPages(int limit) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, Map.of(
                "results", List.of(Map.of("fragrance_slug", "creed-aventus", "public_id", "id-aventus", "name", "Aventus",
                        "brand_name", "Creed", "brand_slug", "creed", "rating", Map.of("score", 0.9))),
                "truncated", true, "next_cursor", "unused-cursor", "offset", 0, "limit", limit,
                "unresolved", List.of(), "_ui", List.of()), null));
        var result = client.searchFragrances(" Aventus ", limit);
        assertThat(result.results()).singleElement().satisfies(row -> {
            assertThat(row.fragranceSlug()).isEqualTo("creed-aventus");
            assertThat(row.publicId()).isEqualTo("id-aventus");
            assertThat(row.name()).isEqualTo("Aventus");
            assertThat(row.brandName()).isEqualTo("Creed");
            assertThat(row.brandSlug()).isEqualTo("creed");
        });
        var request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdk).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("search_fragrances");
        assertThat(request.getValue().arguments()).containsExactlyEntriesOf(Map.of("query", "Aventus", "limit", limit));
        verify(sdk).initialize();
        verifyNoMoreInteractions(sdk); // No profile, filtered search, list_brands, or second page.
    }

    @Test void textualFallbackIgnoresPresentationAndAllowsMissingOptionalBrandFields() {
        when(sdk.callTool(any())).thenReturn(text("{\"results\":[{\"fragrance_slug\":\"creed-aventus\","
                + "\"public_id\":\"id-aventus\",\"name\":\"Aventus\",\"unknown\":{\"large\":true}}]}"));
        var row = client.searchFragrances("Av", 1).results().get(0);
        assertThat(row.brandName()).isNull();
        assertThat(row.brandSlug()).isNull();
    }

    @Test void emptyResultsAreValidAndImmutable() {
        when(sdk.callTool(any())).thenReturn(text("{\"results\":[],\"unresolved\":[],\"truncated\":false}"));
        var result = client.searchFragrances("unknown", 5);
        assertThat(result.results()).isEmpty();
        assertThatThrownBy(() -> result.results().add(null)).isInstanceOf(UnsupportedOperationException.class);
        verify(sdk, times(1)).callTool(any());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n", "A", " A ", "\uD83D\uDC90"})
    void invalidQueryIsRejectedBeforeInitialization(String query) {
        expect(INVALID_REQUEST, () -> client.searchFragrances(query, 5));
        verifyNoInteractions(sdk);
    }
    @ParameterizedTest @ValueSource(ints = {0, -1, 11, Integer.MAX_VALUE})
    void invalidLimitIsRejectedBeforeInitialization(int limit) {
        expect(INVALID_REQUEST, () -> client.searchFragrances("Aventus", limit));
        verifyNoInteractions(sdk);
    }
    @Test void missingCredentialOrClosedClientMakesNoRequest() {
        properties.setApiKey(" ");
        expect(CONFIGURATION, () -> client.searchFragrances("Aventus", 5));
        properties.setApiKey(FAKE_KEY);
        client.close();
        expect(CLOSED, () -> client.searchFragrances("Aventus", 5));
        verifyNoInteractions(sdk);
    }

    @ParameterizedTest @ValueSource(strings = {"<html>UI only</html>", "{bad", "{}", "[]",
            "{\"results\":null}", "{\"results\":{}}", "{\"results\":[]} {}",
            "{\"results\":[null]}", "{\"results\":[{\"fragrance_slug\":\"slug\",\"public_id\":\"id\",\"name\":1}]}",
            "{\"results\":[{\"fragrance_slug\":\"slug\",\"public_id\":\"id\",\"name\":\"Name\",\"brand_name\":{}}]}"})
    void malformedPayloadIsNotCoercedOrExposed(String payload) {
        when(sdk.callTool(any())).thenReturn(text(payload));
        expect(INVALID_RESPONSE, () -> client.searchFragrances("Aventus", 5));
    }
    @Test void providerErrorsAndSdkFailuresAreSanitizedWithoutRetries() {
        when(sdk.callTool(any())).thenReturn(text("{\"error\":{\"code\":\"rpc_timeout\",\"message\":\"" + FAKE_KEY + "\"}}"));
        expect(TOOL_CALL, () -> client.searchFragrances("Aventus", 5));
        when(sdk.callTool(any())).thenThrow(new IllegalStateException(FAKE_KEY, new RuntimeException("private-provider-payload")));
        expect(TOOL_CALL, () -> client.searchFragrances("Aventus", 5));
        verify(sdk, times(2)).callTool(any()); // One per explicit caller invocation.
        verify(sdk).initialize();
        verifyNoMoreInteractions(sdk);
    }
    @Test void nullToolResultAndIsErrorFlagAreRejected() {
        expect(INVALID_RESPONSE, () -> client.searchFragrances("Aventus", 5));
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(FAKE_KEY)), true, null, null));
        expect(TOOL_CALL, () -> client.searchFragrances("Aventus", 5));
    }
    @Test void authenticationStatusIsClassifiedWithoutLeakingHeadersOrCauses() {
        var response = mock(HttpResponse.ResponseInfo.class);
        when(response.statusCode()).thenReturn(401);
        var snapshot = new HttpRequestSnapshot(URI.create("https://api.scentrev.com/mcp/"), "POST",
                HttpHeaders.of(Map.of("Authorization", List.of("Bearer " + FAKE_KEY)), (name, value) -> true));
        when(sdk.callTool(any())).thenThrow(new McpHttpClientTransportAuthorizationException(FAKE_KEY, snapshot, response));
        expect(AUTHENTICATION, () -> client.searchFragrances("Aventus", 5));
        verify(sdk, times(1)).callTool(any());
    }

    private static CallToolResult text(String json) {
        return new CallToolResult(List.of(new TextContent("<html>ignore</html>"), new TextContent(json)), false, null, null);
    }
    private static void expect(ScentRevMcpClientException.FailureType type, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ScentRevMcpClientException.class).hasNoCause()
                .hasMessageNotContaining(FAKE_KEY).hasMessageNotContaining("private-provider-payload")
                .satisfies(error -> assertThat(((ScentRevMcpClientException) error).getFailureType()).isEqualTo(type));
    }
}
