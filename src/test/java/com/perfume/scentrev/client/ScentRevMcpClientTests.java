package com.perfume.scentrev.client;

import static com.perfume.scentrev.client.ScentRevMcpClientException.FailureType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpTransportException;

/** No network, application context, environment credentials or persistence. */
class ScentRevMcpClientTests {

    private static final String SLUG = "creed-aventus";
    private static final String FAKE_KEY = "fake-mcp-unit-credential";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScentRevClientProperties properties = new ScentRevClientProperties();
    private final McpSyncClient sdkClient = mock(McpSyncClient.class);
    @SuppressWarnings("unchecked")
    private final Supplier<McpSyncClient> clientFactory = mock(Supplier.class);

    private ScentRevMcpClient client;

    @BeforeEach
    void setUp() {
        properties.setApiKey(FAKE_KEY);
        when(clientFactory.get()).thenReturn(sdkClient);
        when(sdkClient.initialize()).thenReturn(mock(InitializeResult.class));
        client = new ScentRevMcpClient(properties, objectMapper, clientFactory);
    }

    @Test
    void structuredProfileConvertsToExistingDtoIncludingPreciseDecimals() throws IOException {
        Object domain = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .forType(Object.class).readValue(fixture());
        returns(new CallToolResult(List.of(), false, domain, null));

        ScentRevFragranceProfileResponse profile = client.getFragranceProfile(SLUG);

        assertThat(profile.identity().name()).isEqualTo("Aventus");
        assertThat(profile.identity().brandSlug()).isEqualTo("creed");
        assertThat(profile.identity().fragranceSlug()).isEqualTo(SLUG);
        assertThat(profile.identity().rating().score()).isEqualByComparingTo("4.381234567890123456789");
        assertThat(profile.accords().accords().get(1).score()).isEqualTo(new BigDecimal("0.68"));
        assertThat(profile.perfumers()).hasSize(2);
        assertThat(profile.notePyramid().top()).containsExactly("Pineapple", "Bergamot");
    }

    @Test
    void callsOnlyProfileToolWithAllSupportedSections() {
        returns(profileResult());

        client.getFragranceProfile("canonical-slug-from-caller");

        ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdkClient).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("get_fragrance_profile");
        assertThat(request.getValue().arguments()).containsExactlyEntriesOf(
                Map.of("fragrance_slug", "canonical-slug-from-caller",
                        "sections", List.of("identity", "performance", "appreciation", "notes", "note_pyramid",
                                "accords", "perfumers", "pros_cons", "reminds_of", "price_value"),
                        "include_perfumer_portfolio", false));
        assertThat(request.getValue().meta()).isNull();
        verify(sdkClient).initialize();
        verifyNoMoreInteractions(sdkClient);
    }

    @Test
    void keepsUnknownSourceFieldsAndOriginalPreciseNumbers() throws IOException {
        var root = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(fixture());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).putObject("future_section").put("unknown_field", "retained");
        returns(new CallToolResult(List.of(), false, root, null));
        var profile = client.getFragranceProfile(SLUG);
        assertThat(profile.rawResponse().path("future_section").path("unknown_field").asText()).isEqualTo("retained");
        assertThat(profile.rawResponse().path("identity").path("rating").path("score").decimalValue())
                .isEqualByComparingTo("4.381234567890123456789");
    }

    @Test
    void wearSummaryUsesExactSlugAndSameLazySessionWithoutAutomaticProfileCalls() {
        returns(new CallToolResult(List.of(), false, Map.of("fragrance_slug", SLUG, "public_id", "id-aventus",
                "brand_slug", "creed", "season", Map.of("summer", Map.of("score", 0.8))), null));
        assertThat(client.getWearSummary(SLUG).path("season").path("summer").path("score").decimalValue()).isEqualByComparingTo("0.8");
        var request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdkClient).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("get_wear_summary");
        assertThat(request.getValue().arguments()).containsExactlyEntriesOf(Map.of("fragrance_slug", SLUG));
        verify(sdkClient).initialize();
        verifyNoMoreInteractions(sdkClient);
    }

    @Test
    void wearSummaryProviderErrorStopsWithoutRetryAndDoesNotExposePayload() {
        returns(new CallToolResult(List.of(), false, Map.of("error", Map.of("code", "forbidden", "message", FAKE_KEY)), null));
        assertFailure(() -> client.getWearSummary(SLUG), TOOL_CALL, "get_wear_summary");
        verify(sdkClient, times(1)).callTool(any());
    }

    @Test
    void initializesLazilyAndReusesSessionAcrossExplicitCalls() {
        verifyNoInteractions(clientFactory, sdkClient);
        returns(profileResult());

        client.getFragranceProfile(SLUG);
        client.getFragranceProfile(SLUG);

        verify(clientFactory).get();
        verify(sdkClient).initialize();
        verify(sdkClient, times(2)).callTool(any(CallToolRequest.class));
        verifyNoMoreInteractions(clientFactory, sdkClient);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n"})
    void missingKeyFailsBeforeSdkIsCreated(String key) {
        properties.setApiKey(key);

        assertFailure(() -> client.getFragranceProfile(SLUG), CONFIGURATION, "SCENTREV_API_KEY");

        verifyNoInteractions(clientFactory, sdkClient);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingSlugFailsBeforeSdkIsCreated(String slug) {
        assertFailure(() -> client.getFragranceProfile(slug), INVALID_REQUEST, "fragrance slug");

        verifyNoInteractions(clientFactory, sdkClient);
    }

    @Test
    void toolErrorIsNotConvertedOrRetriedAndDoesNotExposePayload() {
        returns(new CallToolResult(List.of(TextContent.builder(FAKE_KEY).build()), true,
                Map.of("error", Map.of("code", "rpc_timeout", "message", FAKE_KEY)), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), TOOL_CALL, "isError=true");

        verify(sdkClient).callTool(any(CallToolRequest.class));
    }

    @Test
    void nullToolResultFailsClearly() {
        returns(null);

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "no tool result");
    }

    @Test
    void onlyPresentationAndResourcesAreNotTreatedAsProfile() {
        List<Content> presentation = List.of(
                TextContent.builder("<html>" + FAKE_KEY + "</html>").build(),
                TextContent.builder("{\"_ui\": []}").build(),
                EmbeddedResource.builder(TextResourceContents.builder("ui://profile", minimalJson())
                        .mimeType("text/html").build()).build());
        returns(new CallToolResult(presentation, false, null, null));

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "no fragrance profile");
    }

    @Test
    void structuredContentTakesPrecedenceOverConflictingText() {
        returns(new CallToolResult(List.of(TextContent.builder("{\"identity\": {\"name\": \"Wrong\"}}")
                .build()), null, minimalDomain(), null));

        ScentRevFragranceProfileResponse profile = client.getFragranceProfile(SLUG);

        assertThat(profile.identity().name()).isEqualTo("Aventus");
        assertThat(profile.identity().releaseYear()).isNull();
        assertThat(profile.perfumers()).isNull();
        assertThat(profile.notePyramid()).isNull();
        assertThat(profile.accords()).isNull();
    }

    @Test
    void invalidStructuredContentDoesNotFallBackToPresentationText() {
        returns(new CallToolResult(List.of(TextContent.builder(minimalJson()).build()), false,
                Map.of("_ui", List.of()), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "identity object");
    }

    @Test
    void structuredArrayIsRejected() {
        returns(new CallToolResult(List.of(), false, List.of(minimalDomain()), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "identity object");
    }

    @Test
    void emptyIdentityIsRejected() {
        returns(new CallToolResult(List.of(), false, Map.of("identity", Map.of()), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "identity is missing");
    }

    @Test
    void textualJsonIsFallbackAndPreservesDecimalPrecision() throws IOException {
        returns(new CallToolResult(List.of(TextContent.builder("<html>presentation</html>").build(),
                TextContent.builder("{\"_ui\": []}").build(), TextContent.builder(fixture()).build()),
                false, null, null));

        ScentRevFragranceProfileResponse profile = client.getFragranceProfile(SLUG);

        assertThat(profile.identity().name()).isEqualTo("Aventus");
        assertThat(profile.identity().rating().score()).isEqualByComparingTo("4.381234567890123456789");
        assertThat(profile.notePyramid().base()).containsExactly("Musk", "Oakmoss");
    }

    @Test
    void malformedTextualJsonFailsClearly() {
        returns(new CallToolResult(List.of(TextContent.builder("{\"identity\":" + FAKE_KEY).build()),
                false, null, null));

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "malformed textual JSON");
    }

    @Test
    void trailingJsonTokensAreRejected() {
        returns(new CallToolResult(List.of(TextContent.builder(minimalJson() + " {} ").build()),
                false, null, null));

        assertFailure(() -> client.getFragranceProfile(SLUG), INVALID_RESPONSE, "malformed textual JSON");
    }

    @Test
    void incompatibleProfileFieldFailsDtoConversionWithoutLeakingValue() {
        returns(new CallToolResult(List.of(), false, Map.of("identity", Map.of("name", "Aventus",
                "brand_slug", "creed", "fragrance_slug", SLUG, "release_year", FAKE_KEY)), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), DTO_CONVERSION, "ScentRevFragranceProfileResponse");
    }

    @Test
    void providerErrorEnvelopeFailsEvenWhenIsErrorIsFalse() {
        returns(new CallToolResult(List.of(), false,
                Map.of("error", Map.of("code", "no_results", "message", FAKE_KEY)), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), TOOL_CALL, "error payload");
    }

    @Test
    void providerToolNotFoundEnvelopeHasDistinctFailure() {
        returns(new CallToolResult(List.of(), false,
                Map.of("error", Map.of("code", "tool_not_found", "message", FAKE_KEY)), null));

        assertFailure(() -> client.getFragranceProfile(SLUG), TOOL_NOT_FOUND, "tool was not found");
    }

    @Test
    void initializationFailureClosesCandidateAndNeverCallsToolOrRetries() {
        when(sdkClient.initialize()).thenThrow(new IllegalStateException(FAKE_KEY));

        assertFailure(() -> client.getFragranceProfile(SLUG), INITIALIZATION, "initialization failed");

        verify(clientFactory).get();
        verify(sdkClient).initialize();
        verify(sdkClient).close();
        verifyNoMoreInteractions(clientFactory, sdkClient);
    }

    @Test
    void absentInitializationResultIsRejectedAndClosed() {
        when(sdkClient.initialize()).thenReturn(null);

        assertFailure(() -> client.getFragranceProfile(SLUG), INITIALIZATION, "no session result");

        verify(sdkClient).close();
        verify(sdkClient, never()).callTool(any(CallToolRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailureDuringInitializationIsClearAndCredentialSafe(int status) {
        var authentication = authenticationFailure(status);
        when(sdkClient.initialize()).thenThrow(new IllegalStateException(FAKE_KEY, authentication));

        assertFailure(() -> client.getFragranceProfile(SLUG), AUTHENTICATION, "HTTP " + status);

        verify(sdkClient).close();
        verify(sdkClient, never()).callTool(any(CallToolRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailureDuringToolCallIsClearAndCredentialSafe(int status) {
        var authentication = authenticationFailure(status);
        when(sdkClient.callTool(any(CallToolRequest.class))).thenThrow(authentication);

        assertFailure(() -> client.getFragranceProfile(SLUG), AUTHENTICATION, "HTTP " + status);

        verify(sdkClient).callTool(any(CallToolRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {-32601, -32602})
    void missingToolHasDistinctFailure(int code) {
        when(sdkClient.callTool(any(CallToolRequest.class))).thenThrow(McpError.builder(code)
                .message("Tool not found: " + FAKE_KEY).data(Map.of("credential", FAKE_KEY)).build());

        assertFailure(() -> client.getFragranceProfile(SLUG), TOOL_NOT_FOUND, "tool was not found");
    }

    @Test
    void otherProtocolErrorIsToolFailure() {
        when(sdkClient.callTool(any(CallToolRequest.class))).thenThrow(McpError.builder(-32602)
                .message("Invalid arguments: " + FAKE_KEY).build());

        assertFailure(() -> client.getFragranceProfile(SLUG), TOOL_CALL, "call failed");
    }

    @Test
    void sdkTransportFailureDuringInitializationHasClearPhase() {
        when(sdkClient.initialize()).thenThrow(new McpTransportException(FAKE_KEY));

        assertFailure(() -> client.getFragranceProfile(SLUG), TRANSPORT, "during initialization");

        verify(sdkClient).close();
    }

    @Test
    void nestedIoFailureDuringToolCallIsTransportFailure() {
        when(sdkClient.callTool(any(CallToolRequest.class)))
                .thenThrow(new IllegalStateException(FAKE_KEY, new IOException(FAKE_KEY)));

        assertFailure(() -> client.getFragranceProfile(SLUG), TRANSPORT, "during get_fragrance_profile");
    }

    @Test
    void unexpectedToolExceptionHasSafeDiagnostic() {
        when(sdkClient.callTool(any(CallToolRequest.class))).thenThrow(new RuntimeException(FAKE_KEY));

        assertFailure(() -> client.getFragranceProfile(SLUG), TOOL_CALL, "call failed");
    }

    @Test
    void closingInitializedClientClosesSdkOnceAndPreventsFurtherCalls() {
        returns(profileResult());
        client.getFragranceProfile(SLUG);

        client.close();
        client.close();

        verify(sdkClient).close();
        assertFailure(() -> client.getFragranceProfile(SLUG), CLOSED, "client is closed");
        verify(sdkClient).callTool(any(CallToolRequest.class));
    }

    @Test
    void closingUnusedClientNeverCreatesSdkClient() {
        client.close();
        client.close();

        verifyNoInteractions(clientFactory, sdkClient);
        assertFailure(() -> client.getFragranceProfile(SLUG), CLOSED, "client is closed");
    }

    @Test
    void closeFailureDoesNotExposeSdkException() {
        returns(profileResult());
        client.getFragranceProfile(SLUG);
        doThrow(new RuntimeException(FAKE_KEY)).when(sdkClient).close();

        assertFailure(client::close, TRANSPORT, "resources could not be closed");
    }

    @Test
    void sdkAssemblyUsesCorrectEndpointBearerAndJackson2WithoutAutomaticFeatures() {
        var transportBuilder = mock(HttpClientStreamableHttpTransport.Builder.class, RETURNS_SELF);
        var transport = mock(HttpClientStreamableHttpTransport.class);
        var syncSpec = mock(McpClient.SyncSpec.class, RETURNS_SELF);
        when(transportBuilder.build()).thenReturn(transport);
        when(syncSpec.build()).thenReturn(sdkClient);
        try (var httpFactory = mockStatic(HttpClientStreamableHttpTransport.class);
             var mcpFactory = mockStatic(McpClient.class)) {
            httpFactory.when(() -> HttpClientStreamableHttpTransport.builder("https://api.scentrev.com"))
                    .thenReturn(transportBuilder);
            mcpFactory.when(() -> McpClient.sync(transport)).thenReturn(syncSpec);

            assertThat(ScentRevMcpClient.createSdkClient(properties, objectMapper)).isSameAs(sdkClient);

            verify(transportBuilder).endpoint("/mcp/");
            verify(transportBuilder).connectTimeout(Duration.ofSeconds(10));
            verify(transportBuilder).resumableStreams(false);
            verify(transportBuilder).openConnectionOnStartup(false);
            var request = ArgumentCaptor.forClass(HttpRequest.Builder.class);
            verify(transportBuilder).requestBuilder(request.capture());
            assertThat(request.getValue().uri(URI.create("https://api.scentrev.com/mcp/")).build()
                    .headers().firstValue("Authorization")).contains("Bearer " + FAKE_KEY);
            var mapper = ArgumentCaptor.forClass(JacksonMcpJsonMapper.class);
            verify(transportBuilder).jsonMapper(mapper.capture());
            assertThat(mapper.getValue().getObjectMapper()).isNotSameAs(objectMapper);
            assertThat(mapper.getValue().getObjectMapper()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
            assertThat(objectMapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
            verify(syncSpec).requestTimeout(Duration.ofSeconds(30));
            verify(syncSpec).initializationTimeout(Duration.ofSeconds(30));
            verify(syncSpec).enableCallToolSchemaCaching(false);
            verifyNoInteractions(sdkClient);
        }
    }

    @Test
    void actualSdkAndJackson2ProviderCanBeConstructedAndClosedWithoutInitialization() {
        try (McpSyncClient uninitialized = ScentRevMcpClient.createSdkClient(properties, objectMapper)) {
            assertThat(uninitialized.isInitialized()).isFalse();
            assertThat(uninitialized.getClientCapabilities().sampling()).isNull();
            assertThat(uninitialized.getClientInfo().name()).isEqualTo("perfume-scentrev-client");
        }
    }

    private void returns(CallToolResult result) {
        when(sdkClient.callTool(any(CallToolRequest.class))).thenReturn(result);
    }

    @Test
    void filteredSearchUsesExactFourArgumentsAndObservedResponseFields() {
        returns(searchResult());

        var page = client.searchFragrancesFiltered("creed", 10);

        assertThat(page.offset()).isEqualTo(10);
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.totalReturned()).isEqualTo(1);
        assertThat(page.truncated()).isTrue();
        assertThat(page.results()).singleElement().satisfies(row -> {
            assertThat(row.fragranceSlug()).isEqualTo(SLUG);
            assertThat(row.publicId()).isEqualTo("312329ca-0ad9-4a79-a586-2a4b815c18f7");
            assertThat(row.name()).isEqualTo("Aventus");
            assertThat(row.brandSlug()).isNull(); // Actual compact search omits this field.
        });
        var request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdkClient).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("search_fragrances_filtered");
        assertThat(request.getValue().arguments()).containsExactlyEntriesOf(Map.of(
                "filter_brand_slug", "creed", "min_rating_votes", 0, "result_limit", 10, "result_offset", 10));
        assertThat(request.getValue().meta()).isNull();
        verify(sdkClient).initialize();
        verifyNoMoreInteractions(sdkClient);
    }

    @Test
    void searchTextFallbackParsesEmptyPageAndOptionalBrandWithoutReadingPresentation() {
        returns(new CallToolResult(List.of(new TextContent("<html>ignored</html>"), new TextContent("""
                {"results":[],"truncated":false,"offset":0,"limit":10,"total_returned":0,"partial":false}
                """)), false, null, null));
        var page = client.searchFragrancesFiltered("creed", 0);
        assertThat(page.results()).isEmpty();
        assertThat(page.truncated()).isFalse();
        assertThat(page.partial()).isFalse();
    }

    @Test
    void searchAndProfilesReuseOneInitializedSession() {
        when(sdkClient.callTool(any(CallToolRequest.class))).thenReturn(searchResult(), profileResult(), searchResult());
        client.searchFragrancesFiltered("creed", 0);
        client.getFragranceProfile(SLUG);
        client.searchFragrancesFiltered("creed", 10);
        verify(sdkClient).initialize();
        verify(clientFactory).get();
        verify(sdkClient, times(3)).callTool(any(CallToolRequest.class));
        verifyNoMoreInteractions(clientFactory, sdkClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"results\":null}", "{\"results\":{}}", "[]", "{}",
            "{\"results\":[],\"offset\":\"10\"}", "{\"results\":[],\"offset\":1.5}",
            "{\"results\":[],\"limit\":2147483648}", "{\"results\":[],\"truncated\":\"false\"}",
            "{\"results\":[],\"partial\":1}"})
    void rejectsMalformedStructuredSearch(String json) throws IOException {
        returns(new CallToolResult(List.of(new TextContent("{\"results\":[]}")), false,
                objectMapper.readValue(json, Object.class), null));
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), INVALID_RESPONSE, "filtered search");
    }

    @Test
    void searchRejectsMalformedRowsAsDtoConversionFailure() {
        returns(new CallToolResult(List.of(), false, Map.of("results", List.of(Map.of("fragrance_slug", List.of("bad")))), null));
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), DTO_CONVERSION, "ScentRevFilteredSearchResponse");
    }

    @Test
    void searchRejectsNullToolResponse() {
        returns(null);
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), INVALID_RESPONSE, "no search tool result");
    }

    @Test
    void searchRejectsToolErrorWithoutEchoingProviderSecrets() {
        returns(new CallToolResult(List.of(new TextContent(FAKE_KEY)), true, Map.of("error", FAKE_KEY), null));
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), TOOL_CALL, "search_fragrances_filtered");
    }

    @Test
    void searchRejectsErrorEnvelopeWithoutEchoingProviderSecrets() {
        returns(new CallToolResult(List.of(), false, Map.of("error", Map.of("code", "invalid_param", "message", FAKE_KEY)), null));
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), TOOL_CALL, "error payload");
    }

    @Test
    void searchReportsMissingToolSafely() {
        when(sdkClient.callTool(any(CallToolRequest.class)))
                .thenThrow(McpError.builder(-32601).message(FAKE_KEY).build());
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), TOOL_NOT_FOUND, "search_fragrances_filtered");
    }

    @Test
    void searchReportsTransportFailureSafely() {
        when(sdkClient.callTool(any(CallToolRequest.class))).thenThrow(new McpTransportException(FAKE_KEY));
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), TRANSPORT, "during search_fragrances_filtered");
    }

    @Test
    void searchReportsAuthenticationFailureSafely() {
        var authentication = authenticationFailure(401);
        when(sdkClient.callTool(any(CallToolRequest.class))).thenThrow(authentication);
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), AUTHENTICATION, "HTTP 401");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void searchRejectsMissingBrandBeforeInitialization(String brand) {
        assertFailure(() -> client.searchFragrancesFiltered(brand, 0), INVALID_REQUEST, "brand slug");
        verifyNoInteractions(clientFactory, sdkClient);
    }

    @Test
    void searchRejectsNegativeOffsetBeforeInitialization() {
        assertFailure(() -> client.searchFragrancesFiltered("creed", -1), INVALID_REQUEST, "nonnegative offset");
        verifyNoInteractions(clientFactory, sdkClient);
    }

    @Test
    void searchRequiresKeyBeforeInitialization() {
        properties.setApiKey(" ");
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), CONFIGURATION, "SCENTREV_API_KEY");
        verifyNoInteractions(clientFactory, sdkClient);
    }

    @Test
    void searchRejectsClosedClientBeforeInitialization() {
        client.close();
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), CLOSED, "closed");
        verifyNoInteractions(clientFactory, sdkClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>only UI</html>", "{bad", "{\"results\":[]} {\"truncated\":false}"})
    void searchRejectsMissingOrInvalidTextJson(String text) {
        returns(new CallToolResult(List.of(new TextContent(text)), false, null, null));
        assertFailure(() -> client.searchFragrancesFiltered("creed", 0), INVALID_RESPONSE, "valid textual JSON");
    }

    private static CallToolResult searchResult() {
        // Observed compact response shape, reduced to one row; unused metrics/cursor are ignored.
        return new CallToolResult(List.of(new TextContent("<html>ignored</html>")), false, Map.of(
                "results", List.of(Map.of("fragrance_slug", SLUG, "public_id", "312329ca-0ad9-4a79-a586-2a4b815c18f7",
                        "name", "Aventus", "rating", Map.of("score", 0.9))),
                "offset", 10, "limit", 10, "truncated", true, "total_returned", 1,
                "next_cursor", "ignored-provider-cursor", "unresolved", List.of()), null);
    }

    private static CallToolResult profileResult() {
        return new CallToolResult(List.of(), false, minimalDomain(), null);
    }

    private static Map<String, Object> minimalDomain() {
        return Map.of("identity", Map.of("name", "Aventus", "brand_slug", "creed", "fragrance_slug", SLUG),
                "_ui", List.of(Map.of("html", "<html>presentation</html>")));
    }

    private static String minimalJson() {
        return "{\"identity\":{\"name\":\"Aventus\",\"brand_slug\":\"creed\",\"fragrance_slug\":\"creed-aventus\"}}";
    }

    private String fixture() throws IOException {
        try (var stream = getClass().getResourceAsStream("/scentrev/rich-profile.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static McpHttpClientTransportAuthorizationException authenticationFailure(int status) {
        HttpResponse.ResponseInfo response = mock(HttpResponse.ResponseInfo.class);
        when(response.statusCode()).thenReturn(status);
        HttpRequestSnapshot snapshot = new HttpRequestSnapshot(URI.create("https://api.scentrev.com/mcp/"),
                "POST", HttpHeaders.of(Map.of("Authorization", List.of("Bearer " + FAKE_KEY)), (name, value) -> true));
        return new McpHttpClientTransportAuthorizationException(FAKE_KEY, snapshot, response);
    }

    private static void assertFailure(ThrowingCallable operation, FailureType type, String messageFragment) {
        assertThatThrownBy(operation).isInstanceOf(ScentRevMcpClientException.class)
                .hasMessageContaining(messageFragment).hasNoCause().satisfies(exception -> {
                    assertThat(((ScentRevMcpClientException) exception).getFailureType()).isEqualTo(type);
                    assertThat(exception.getMessage()).doesNotContain(FAKE_KEY);
                    assertThat(exception.toString()).doesNotContain(FAKE_KEY);
                    assertThat(exception.getSuppressed()).isEmpty();
                });
    }
}
