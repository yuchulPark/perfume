package com.perfume.scentrev.client;

import static com.perfume.scentrev.client.ScentRevMcpClientException.FailureType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;
import com.perfume.scentrev.config.ScentRevClientProperties;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpTransportException;

/** Only the existing SDK boundary is mocked; there are no environment credentials or HTTP calls. */
class ScentRevMcpBrandListTests {

    private static final String FAKE_SECRET = "fake-secret-authorization";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScentRevClientProperties properties = new ScentRevClientProperties();
    private final McpSyncClient sdk = mock(McpSyncClient.class);
    private ScentRevMcpClient client;

    @BeforeEach
    void setUp() {
        properties.setApiKey(FAKE_SECRET);
        when(sdk.initialize()).thenReturn(mock(InitializeResult.class));
        client = new ScentRevMcpClient(properties, mapper, () -> sdk);
    }

    @Test
    void exactListBrandsArgumentsAndObservedSchemaAreUsedWithoutQueryOrCursor() {
        when(sdk.callTool(any())).thenReturn(observedShape());
        var result = client.listBrands(10);
        assertThat(result.offset()).isEqualTo(10);
        assertThat(result.limit()).isEqualTo(10);
        assertThat(result.totalReturned()).isEqualTo(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.brands()).singleElement().satisfies(brand -> {
            assertThat(brand.brandSlug()).isEqualTo("the-new-dawn");
            assertThat(brand.brandName()).isEqualTo("Новая Заря (The New Dawn)");
        });
        var request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdk).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("list_brands");
        assertThat(request.getValue().arguments()).containsExactlyEntriesOf(Map.of(
                "result_limit", 10, "result_offset", 10, "include_unbranded", false));
        assertThat(request.getValue().meta()).isNull();
        verify(sdk).initialize();
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void brandListingAndExistingOperationsShareOneInitializedSession() {
        when(sdk.callTool(any())).thenReturn(observedShape(), new CallToolResult(List.of(), false, Map.of(
                "results", List.of(), "offset", 0, "limit", 10, "total_returned", 0, "truncated", false), null),
                new CallToolResult(List.of(), false, Map.of("identity", Map.of(
                        "name", "Aventus", "brand_slug", "creed", "fragrance_slug", "creed-aventus")), null));
        client.listBrands(0);
        client.searchFragrancesFiltered("creed", 0);
        client.getFragranceProfile("creed-aventus");
        verify(sdk).initialize();
        verify(sdk, times(3)).callTool(any());
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void textualFallbackIgnoresPresentationAndParsesEmptyCatalog() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent("<html>UI</html>"),
                new TextContent("{\"brands\":[],\"offset\":0,\"limit\":10,\"total_returned\":0,\"truncated\":false}")), false, null, null));
        var result = client.listBrands(0);
        assertThat(result.brands()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"brands\":null}", "{\"brands\":{}}",
            "{\"brands\":[],\"offset\":\"10\"}", "{\"brands\":[],\"offset\":0.5}",
            "{\"brands\":[],\"limit\":2147483648}", "{\"brands\":[],\"total_returned\":\"1\"}",
            "{\"brands\":[],\"truncated\":\"false\"}"})
    void malformedStructuredResultWinsOverTextFallbackAndFailsSafely(String json) throws IOException {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent("{\"brands\":[]}")),
                false, mapper.readValue(json, Object.class), null));
        assertFailure(INVALID_RESPONSE, "brand list");
    }

    @Test
    void incompatibleBrandFieldsFailDtoConversion() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("brands", List.of(Map.of("brand_slug", List.of("invalid")))), null));
        assertFailure(DTO_CONVERSION, "ScentRevBrandListResponse");
    }

    @Test
    void nullResultFailsClearly() {
        when(sdk.callTool(any())).thenReturn(null);
        assertFailure(INVALID_RESPONSE, "no brand-list tool result");
    }

    @Test
    void toolErrorDiscardsRawPayload() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(FAKE_SECRET)), true, null, null));
        assertFailure(TOOL_CALL, "list_brands reported isError=true");
    }

    @Test
    void observedStringErrorContractDiscardsProviderMessage() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("error", "rpc_timeout", "message", FAKE_SECRET, "hint", FAKE_SECRET), null));
        assertFailure(TOOL_CALL, "list_brands returned an error payload");
    }

    @Test
    void missingToolEnvelopeIsClassified() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, Map.of("error", "tool_not_found"), null));
        assertFailure(TOOL_NOT_FOUND, "list_brands tool was not found");
    }

    @Test
    void missingToolJsonRpcErrorIsClassified() {
        when(sdk.callTool(any())).thenThrow(McpError.builder(-32601).message(FAKE_SECRET).build());
        assertFailure(TOOL_NOT_FOUND, "list_brands tool was not found");
    }

    @Test
    void transportErrorDoesNotExposeRawCause() {
        when(sdk.callTool(any())).thenThrow(new McpTransportException(FAKE_SECRET));
        assertFailure(TRANSPORT, "during list_brands");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>UI only</html>", "{broken", "{\"brands\":[]} {\"truncated\":false}"})
    void invalidTextualResultIsRejected(String text) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent(text)), false, null, null));
        assertFailure(INVALID_RESPONSE, "valid textual JSON");
    }

    @Test
    void negativeOffsetIsRejectedWithoutSdkInitialization() {
        assertThatThrownBy(() -> client.listBrands(-1)).isInstanceOf(ScentRevMcpClientException.class)
                .hasMessageContaining("nonnegative offset");
        verifyNoInteractions(sdk);
    }

    @Test
    void missingKeyIsRejectedWithoutSdkInitialization() {
        properties.setApiKey(" ");
        assertFailure(CONFIGURATION, "SCENTREV_API_KEY");
        verifyNoInteractions(sdk);
    }

    @Test
    void closedClientIsRejectedWithoutSdkInitialization() {
        client.close();
        assertFailure(CLOSED, "closed");
        verifyNoInteractions(sdk);
    }

    private void assertFailure(FailureType type, String fragment) {
        assertThatThrownBy(() -> client.listBrands(0)).isInstanceOf(ScentRevMcpClientException.class)
                .hasMessageContaining(fragment).hasMessageNotContaining(FAKE_SECRET).hasNoCause()
                .satisfies(error -> assertThat(((ScentRevMcpClientException) error).getFailureType()).isEqualTo(type));
    }

    private static CallToolResult observedShape() {
        // Observed offset-10 response shape, reduced to one row; count and cursor are deliberately unused.
        return new CallToolResult(List.of(), false, Map.of("brands", List.of(Map.of("brand_slug", "the-new-dawn",
                "brand_name", "Новая Заря (The New Dawn)", "fragrance_count", 411)), "limit", 10,
                "offset", 10, "total_returned", 1, "truncated", true, "next_cursor", "eyJvIjoyMH0"), null);
    }
}
