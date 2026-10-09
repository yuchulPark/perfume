package com.perfume.scentrev.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.curated.ScentRevCuratedDeepResolutionSmokeTests.DiagnosticClient;
import com.perfume.scentrev.curated.ScentRevCuratedDeepResolutionSmokeTests.DiagnosticException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Mocked SDK only: verifies call limits, identity arguments, and exclusion of unrelated search rows. */
class ScentRevCuratedDeepResolutionTests {
    private static final String DSQUARED_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MIKA_ID = "00000000-0000-0000-0000-000000000002";
    private final McpSyncClient sdk = mock(McpSyncClient.class);
    private final DiagnosticClient client = new DiagnosticClient(sdk, new ObjectMapper());
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(console, true, StandardCharsets.UTF_8);

    @BeforeEach
    void defaultsToIdentityRowsAndEmptySearches() {
        when(sdk.callTool(any())).thenAnswer(invocation -> {
            CallToolRequest request = invocation.getArgument(0);
            return request.name().equals("get_identity")
                    ? identity(request.arguments().get("public_id").toString()) : search(List.of());
        });
    }

    @Test
    void sixExactIdentityIdentifiersAndTwoSearchesUseEightCallsWithoutPagination() {
        assertThat(ScentRevCuratedDeepResolutionSmokeTests.report(client, out)).isZero();
        var requests = requests(8);
        assertThat(requests.subList(0, 6)).allSatisfy(request -> {
            assertThat(request.name()).isEqualTo("get_identity");
            assertThat(request.arguments()).containsOnlyKeys("public_id");
        });
        assertThat(requests.subList(0, 6)).extracting(request -> request.arguments().get("public_id"))
                .containsExactly("5002fec8-8aed-4c74-835f-3959edfcf645", "54a7bf82-0a09-4458-9cb3-22d754e1264b",
                        "35645322-bb48-4b34-a06c-c57741b2284a", "c48f3d19-2603-4ed7-959b-026260b5e691",
                        "91bed0a1-4296-4304-ac31-148d1aa75a23", "ef6f9492-fbb5-4f41-a721-f3cede69538f");
        assertThat(requests.get(6).name()).isEqualTo("search_fragrances");
        assertThat(requests.get(6).arguments()).containsExactlyEntriesOf(Map.of("query", "Original Wood", "limit", 10));
        assertThat(requests.get(7).name()).isEqualTo("search_fragrances");
        assertThat(requests.get(7).arguments()).containsExactlyEntriesOf(Map.of("query", "Lady Muscat", "limit", 10));
        assertThat(output()).contains("brand_name = Observed provider", "brand_slug = observed-provider")
                .contains("fragrance_name = Observed fragrance", "fragrance_slug = observed-provider-fragrance")
                .contains("[IDENTITY] Ed Hardy [curated-111]", "[IDENTITY] Chloé [curated-147]",
                        "MCP tool calls attempted = 8");
        assertThat(output().split("NO_USABLE_RESULT", -1)).hasSize(3);
        // search() includes a cursor and truncated=true, which never cause another request.
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void oneClearProductMatchPerSearchWithoutBrandMetadataAllowsExactlyTenCalls() {
        respondToSearch("Original Wood", List.of(row("Original Wood", "dsquared2-original-wood", DSQUARED_ID)));
        respondToSearch("Lady Muscat", List.of(row("Lady Muscat", "mika-lokka-lady-muscat", MIKA_ID)));
        assertThat(ScentRevCuratedDeepResolutionSmokeTests.report(client, out)).isZero();
        var requests = requests(10);
        assertThat(requests).filteredOn(request -> request.name().equals("get_identity")).hasSize(8);
        assertThat(requests).filteredOn(request -> request.name().equals("search_fragrances")).hasSize(2);
        assertThat(requests.get(7).arguments()).containsExactlyEntriesOf(Map.of("public_id", DSQUARED_ID));
        assertThat(requests.get(9).arguments()).containsExactlyEntriesOf(Map.of("public_id", MIKA_ID));
        assertThat(output().split("FOLLOW_UP_IDENTITY", -1)).hasSize(3);
        assertThat(output()).contains("MCP tool calls attempted = 10");
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void unrelatedProductsOrContradictingBrandMetadataNeverTriggerIdentityFanOut() {
        var conflicting = Map.of("name", "Original Wood", "fragrance_slug", "dsquared2-original-wood",
                "public_id", DSQUARED_ID, "brand_name", "Other brand");
        respondToSearch("Original Wood", List.of(conflicting,
                row("Another fragrance", "dsquared2-another-fragrance", MIKA_ID)));
        respondToSearch("Lady Muscat", List.of(row("Lady Muscat", "other-brand-lady-muscat", MIKA_ID)));
        assertThat(ScentRevCuratedDeepResolutionSmokeTests.report(client, out)).isZero();
        requests(8);
        assertThat(output()).doesNotContain("FOLLOW_UP_IDENTITY");
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void multipleMatchingPublicIdsAreReportedWithoutChoosingOrFetchingEither() {
        respondToSearch("Original Wood", List.of(row("Original Wood", "dsquared2-original-wood", DSQUARED_ID),
                row("Original Wood", "dsquared2-original-wood", MIKA_ID)));
        assertThat(ScentRevCuratedDeepResolutionSmokeTests.report(client, out)).isZero();
        requests(8);
        assertThat(output()).contains("RESULT 1", "RESULT 2", "NO_USABLE_RESULT").doesNotContain("FOLLOW_UP_IDENTITY");
        verifyNoMoreInteractions(sdk);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorsConsumeTheirCallSlotAndDoNotLeakPayloadsOrPreventTheOtherTargets(boolean sdkFailure) {
        doAnswer(invocation -> {
            CallToolRequest request = invocation.getArgument(0);
            if (request.arguments().containsValue("5002fec8-8aed-4c74-835f-3959edfcf645")) {
                if (sdkFailure) { throw new IllegalStateException("private-diagnostic-credential"); }
                return structured(Map.of("error", Map.of("code", "rpc_timeout", "message", "private-diagnostic-credential")));
            }
            return request.name().equals("get_identity")
                    ? identity(request.arguments().get("public_id").toString()) : search(List.of());
        }).when(sdk).callTool(any());
        assertThat(ScentRevCuratedDeepResolutionSmokeTests.report(client, out)).isEqualTo(1);
        requests(8);
        assertThat(output()).contains("ERROR", "error = TOOL_CALL", "[SEARCH] 미카로카 [curated-133]")
                .doesNotContain("private-diagnostic-credential");
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void eleventhToolCallIsRejectedBeforeInvokingTheSdk() {
        for (int index = 0; index < 10; index++) { client.getIdentity(DSQUARED_ID); }
        assertThatThrownBy(() -> client.getIdentity(DSQUARED_ID)).isInstanceOf(DiagnosticException.class)
                .hasMessage("Diagnostic cannot exceed ten MCP tool calls.").hasNoCause();
        assertThat(client.calls()).isEqualTo(10);
        requests(10);
        verifyNoMoreInteractions(sdk);
    }

    @Test
    void wrappedTextIdentityUsesTheExistingDtoWithoutReconstructingTheProviderBrand() {
        doReturn(new CallToolResult(List.of(new TextContent("<html>ignore</html>"),
                new TextContent("""
                        {"identity":{"name":"Ed Hardy Villain for Women","public_id":"c48f3d19-2603-4ed7-959b-026260b5e691",
                        "fragrance_slug":"christian-audigier-ed-hardy-villain-for-women",
                        "brand_name":"Christian Audigier","brand_slug":"christian-audigier"}}
                        """)), false, null, null)).when(sdk).callTool(any());
        var identity = client.getIdentity("c48f3d19-2603-4ed7-959b-026260b5e691");
        assertThat(identity.brandName()).isEqualTo("Christian Audigier");
        assertThat(identity.brandSlug()).isEqualTo("christian-audigier");
        requests(1);
        verifyNoMoreInteractions(sdk);
    }

    private void respondToSearch(String query, List<Map<String, String>> rows) {
        doReturn(search(rows)).when(sdk).callTool(argThat(request -> request != null && request.name().equals("search_fragrances")
                && query.equals(request.arguments().get("query"))));
    }

    private List<CallToolRequest> requests(int count) {
        var captured = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdk, times(count)).callTool(captured.capture());
        return captured.getAllValues();
    }

    private String output() { return console.toString(StandardCharsets.UTF_8); }

    private static Map<String, String> row(String name, String slug, String id) {
        return Map.of("name", name, "fragrance_slug", slug, "public_id", id);
    }

    private static CallToolResult identity(String id) {
        return structured(Map.of("name", "Observed fragrance", "public_id", id,
                "fragrance_slug", "observed-provider-fragrance", "brand_name", "Observed provider", "brand_slug", "observed-provider"));
    }

    private static CallToolResult search(List<Map<String, String>> rows) {
        return structured(Map.of("results", rows, "truncated", true, "next_cursor", "ignored-cursor"));
    }

    private static CallToolResult structured(Object domain) {
        return new CallToolResult(List.of(), false, domain, null);
    }
}
