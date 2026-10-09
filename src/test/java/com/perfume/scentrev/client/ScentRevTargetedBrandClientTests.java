package com.perfume.scentrev.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.config.ScentRevClientProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

class ScentRevTargetedBrandClientTests {
    private final McpSyncClient sdk = mock(McpSyncClient.class);
    private final ScentRevClientProperties properties = new ScentRevClientProperties();

    private ScentRevMcpClient client() {
        properties.setApiKey("fake-offline-key");
        when(sdk.initialize()).thenReturn(mock(InitializeResult.class));
        return new ScentRevMcpClient(properties, new ObjectMapper(), () -> sdk);
    }

    @Test
    void sendsExactQueriedContractOnceEvenIfFirstPageIsTruncated() {
        var payload = Map.of("brands", List.of(Map.of("brand_name", "Frederic Malle", "brand_slug", "provider-slug", "fragrance_count", 42)),
                "truncated", true, "next_cursor", "never-follow-this", "offset", 0, "limit", 10, "total_returned", 1);
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, payload, null));
        var result = client().searchBrands("Frederic Malle", 10);
        assertThat(result.brands().get(0).brandSlug()).isEqualTo("provider-slug");
        assertThat(result.brands().get(0).fragranceCount()).isEqualTo(42L);
        assertThat(result.truncated()).isTrue();
        var request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdk).initialize();
        verify(sdk).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("list_brands");
        assertThat(request.getValue().arguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "query", "Frederic Malle", "result_limit", 10, "result_offset", 0, "include_unbranded", false));
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void acceptsTextualCandidateAndSmallOperatorLimit() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(new TextContent("""
                {"brands":[{"brand_name":"Creed","brand_slug":"creed","fragrance_count":null}],"truncated":false}
                """)), false, null, null));
        assertThat(client().searchBrands("Creed", 3).brands().get(0).fragranceCount()).isNull();
        var request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdk).callTool(request.capture());
        assertThat(request.getValue().arguments()).containsEntry("result_limit", 3).containsEntry("query", "Creed");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void refusesQuerylessCallsBeforeSdkInitialization(String query) {
        var client = client();
        assertThatThrownBy(() -> client.searchBrands(query, 10)).isInstanceOf(ScentRevMcpClientException.class);
        verifyNoInteractions(sdk);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11, 100})
    void refusesOutOfRangeLimitsWithoutCalls(int limit) {
        var client = client();
        assertThatThrownBy(() -> client.searchBrands("Creed", limit)).isInstanceOf(ScentRevMcpClientException.class);
        verifyNoInteractions(sdk);
    }

    @Test
    void providerNoResultsBecomesEmptyTargetedResponseWithoutFallback() {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false, Map.of("error", "no_results"), null));
        assertThat(client().searchBrands("Missing", 10).brands()).isEmpty();
        verify(sdk).initialize();
        verify(sdk).callTool(any());
        verifyNoMoreInteractions(sdk);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rpc_timeout", "rate_limit", "access_denied", "policy_denied", "authentication"})
    void providerErrorsAreSurfacedSafelyWithoutRetriesOrPayloadLogging(String code) {
        when(sdk.callTool(any())).thenReturn(new CallToolResult(List.of(), false,
                Map.of("error", code, "message", "SECRET-PROVIDER-MESSAGE"), null));
        assertThatThrownBy(() -> client().searchBrands("Creed", 10)).isInstanceOf(ScentRevMcpClientException.class)
                .hasMessage("ScentRev MCP list_brands returned an error payload.").hasNoCause();
        verify(sdk).initialize();
        verify(sdk).callTool(any());
        verifyNoMoreInteractions(sdk);
    }
}
