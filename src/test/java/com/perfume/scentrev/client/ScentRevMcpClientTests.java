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
    void callsOnlyProfileToolWithExactPhase1Arguments() {
        returns(profileResult());

        client.getFragranceProfile("canonical-slug-from-caller");

        ArgumentCaptor<CallToolRequest> request = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(sdkClient).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("get_fragrance_profile");
        assertThat(request.getValue().arguments()).containsExactlyEntriesOf(
                Map.of("fragrance_slug", "canonical-slug-from-caller",
                        "sections", List.of("identity", "perfumers", "notes", "accords"),
                        "include_perfumer_portfolio", false));
        assertThat(request.getValue().meta()).isNull();
        verify(sdkClient).initialize();
        verifyNoMoreInteractions(sdkClient);
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
