package com.perfume.scentrev.client;

import static com.perfume.scentrev.client.ScentRevMcpClientException.FailureType.*;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpTransportException;

/** Focused synchronous profile/search operations over a lazily initialized, reusable SDK client. */
public class ScentRevMcpClient implements AutoCloseable {

    private static final String PROFILE_TOOL = "get_fragrance_profile";
    private static final String SEARCH_TOOL = "search_fragrances_filtered";
    public static final int SEARCH_PAGE_LIMIT = 10;

    private final ScentRevClientProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectReader domainReader;
    private final Supplier<McpSyncClient> clientFactory;

    private McpSyncClient sdkClient;
    private boolean closed;

    public ScentRevMcpClient(ScentRevClientProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, () -> createSdkClient(properties, objectMapper));
    }

    /** Narrow test boundary: mock only SDK initialization, tool invocation and closure. */
    ScentRevMcpClient(ScentRevClientProperties properties, ObjectMapper objectMapper,
                      Supplier<McpSyncClient> clientFactory) {
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.domainReader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clientFactory = Objects.requireNonNull(clientFactory);
    }

    public synchronized ScentRevFragranceProfileResponse getFragranceProfile(String fragranceSlug) {
        if (closed) {
            throw failure(CLOSED, "ScentRev MCP client is closed.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(CONFIGURATION, "ScentRev MCP requires a nonblank SCENTREV_API_KEY.");
        }
        if (fragranceSlug == null || fragranceSlug.isBlank()) {
            throw failure(INVALID_REQUEST, "ScentRev MCP requires a nonblank fragrance slug.");
        }

        initializeIfNeeded();
        CallToolResult result;
        try {
            result = sdkClient.callTool(CallToolRequest.builder(PROFILE_TOOL)
                    .arguments(Map.of(
                            "fragrance_slug", fragranceSlug,
                            "sections", List.of("identity", "perfumers", "notes", "accords"),
                            "include_perfumer_portfolio", false)).build());
        } catch (RuntimeException exception) {
            throw sdkFailure(exception, false);
        }
        return extractProfile(result);
    }

    /** One brand, offset pagination, and no implicit 200-vote filter. */
    public synchronized ScentRevFilteredSearchResponse searchFragrancesFiltered(String brandSlug, int resultOffset) {
        if (closed) {
            throw failure(CLOSED, "ScentRev MCP client is closed.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(CONFIGURATION, "ScentRev MCP requires a nonblank SCENTREV_API_KEY.");
        }
        if (brandSlug == null || brandSlug.isBlank() || resultOffset < 0) {
            throw failure(INVALID_REQUEST, "ScentRev MCP search requires a nonblank brand slug and nonnegative offset.");
        }
        initializeIfNeeded();
        CallToolResult result;
        try {
            result = sdkClient.callTool(CallToolRequest.builder(SEARCH_TOOL)
                    .arguments(Map.of("filter_brand_slug", brandSlug, "min_rating_votes", 0,
                            "result_limit", SEARCH_PAGE_LIMIT, "result_offset", resultOffset)).build());
        } catch (RuntimeException exception) {
            throw sdkFailure(exception, false, SEARCH_TOOL);
        }
        return extractSearch(result);
    }

    private ScentRevFilteredSearchResponse extractSearch(CallToolResult result) {
        if (result == null) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP returned no search tool result.");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw failure(TOOL_CALL, "ScentRev MCP search_fragrances_filtered reported isError=true.");
        }
        if (result.structuredContent() != null) {
            try {
                return convertSearch(objectMapper.valueToTree(result.structuredContent()));
            } catch (IllegalArgumentException exception) {
                throw failure(DTO_CONVERSION, "ScentRev MCP search structured content could not be converted to JSON.");
            }
        }
        for (var content : result.content()) {
            if (!(content instanceof TextContent textContent)) {
                continue;
            }
            String text = textContent.text().strip();
            if (!text.startsWith("{") && !text.startsWith("[")) {
                continue;
            }
            JsonNode domain;
            try {
                domain = domainReader.readTree(text);
            } catch (JsonProcessingException exception) {
                continue;
            }
            if (domain.isObject() && (domain.has("results") || domain.hasNonNull("error"))) {
                return convertSearch(domain);
            }
        }
        throw failure(INVALID_RESPONSE, "ScentRev MCP returned no filtered search in structured content or valid textual JSON.");
    }

    private ScentRevFilteredSearchResponse convertSearch(JsonNode domain) {
        if (domain != null && domain.isObject() && domain.hasNonNull("error")) {
            String code = domain.path("error").path("code").asText();
            if ("tool_not_found".equals(code) || "unknown_tool".equals(code)) {
                throw failure(TOOL_NOT_FOUND, "ScentRev MCP search_fragrances_filtered tool was not found.");
            }
            throw failure(TOOL_CALL, "ScentRev MCP search_fragrances_filtered returned an error payload.");
        }
        if (domain == null || !domain.isObject() || !domain.path("results").isArray()) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP filtered search must contain a results array.");
        }
        // Reject coercion of pagination strings/fractions; missing fields are checked by discovery.
        for (String field : List.of("offset", "limit", "total_returned")) {
            JsonNode value = domain.path(field);
            if (!value.isMissingNode() && !value.isNull()
                    && (!value.isIntegralNumber() || !value.canConvertToInt())) {
                throw failure(INVALID_RESPONSE, "ScentRev MCP filtered search has invalid numeric pagination metadata.");
            }
        }
        for (String field : List.of("truncated", "partial")) {
            JsonNode value = domain.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.isBoolean()) {
                throw failure(INVALID_RESPONSE, "ScentRev MCP filtered search has invalid boolean pagination metadata.");
            }
        }
        try {
            return objectMapper.treeToValue(domain, ScentRevFilteredSearchResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(DTO_CONVERSION, "ScentRev MCP filtered search could not be converted to ScentRevFilteredSearchResponse.");
        }
    }

    private void initializeIfNeeded() {
        if (sdkClient != null) {
            return;
        }
        McpSyncClient candidate = null;
        try {
            candidate = clientFactory.get();
            if (candidate == null || candidate.initialize() == null) {
                throw failure(INITIALIZATION, "ScentRev MCP initialization returned no session result.");
            }
            sdkClient = candidate;
        } catch (RuntimeException exception) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException ignored) {
                    // Preserve the original safe initialization diagnostic, never a raw SDK cause.
                }
            }
            if (exception instanceof ScentRevMcpClientException safeException) {
                throw safeException;
            }
            throw sdkFailure(exception, true);
        }
    }

    static McpSyncClient createSdkClient(ScentRevClientProperties properties, ObjectMapper objectMapper) {
        // Keep untyped structuredContent decimals precise without changing the application's mapper.
        ObjectMapper protocolMapper = objectMapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(properties.getBaseUrl())
                .endpoint("/mcp/")
                .connectTimeout(Duration.ofSeconds(10))
                .requestBuilder(HttpRequest.newBuilder()
                        .header("Authorization", "Bearer " + properties.getApiKey()))
                .jsonMapper(new JacksonMcpJsonMapper(protocolMapper))
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .build();
        try {
            return McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(30))
                    .clientInfo(Implementation.builder("perfume-scentrev-client", "0.0.1").build())
                    .capabilities(ClientCapabilities.builder().build())
                    .enableCallToolSchemaCaching(false)
                    .build();
        } catch (RuntimeException exception) {
            transport.close();
            throw exception;
        }
    }

    private ScentRevFragranceProfileResponse extractProfile(CallToolResult result) {
        if (result == null) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP returned no tool result.");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw failure(TOOL_CALL, "ScentRev MCP get_fragrance_profile reported isError=true.");
        }
        if (result.structuredContent() != null) {
            JsonNode domain;
            try {
                domain = objectMapper.valueToTree(result.structuredContent());
            } catch (IllegalArgumentException exception) {
                throw failure(DTO_CONVERSION, "ScentRev MCP structured content could not be converted to JSON.");
            }
            return convertProfile(domain);
        }

        boolean malformedJson = false;
        for (var content : result.content()) {
            if (!(content instanceof TextContent textContent)) {
                continue; // Embedded resources, images and UI HTML are not domain data.
            }
            String text = textContent.text().strip();
            if (!text.startsWith("{") && !text.startsWith("[")) {
                continue;
            }
            JsonNode domain;
            try {
                domain = domainReader.readTree(text);
            } catch (JsonProcessingException exception) {
                malformedJson = true;
                continue;
            }
            if (domain.isObject() && (domain.has("identity") || domain.hasNonNull("error"))) {
                return convertProfile(domain);
            }
        }
        throw failure(INVALID_RESPONSE, malformedJson
                ? "ScentRev MCP returned malformed textual JSON and no fragrance profile."
                : "ScentRev MCP returned no fragrance profile in structured content or textual JSON.");
    }

    private ScentRevFragranceProfileResponse convertProfile(JsonNode domain) {
        if (domain != null && domain.isObject() && domain.hasNonNull("error")) {
            String code = domain.path("error").path("code").asText();
            if ("tool_not_found".equals(code) || "unknown_tool".equals(code)) {
                throw failure(TOOL_NOT_FOUND, "ScentRev MCP get_fragrance_profile tool was not found.");
            }
            throw failure(TOOL_CALL, "ScentRev MCP get_fragrance_profile returned an error payload.");
        }
        if (domain == null || !domain.isObject() || !domain.path("identity").isObject()) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP profile must contain an identity object.");
        }
        ScentRevFragranceProfileResponse profile;
        try {
            profile = objectMapper.treeToValue(domain, ScentRevFragranceProfileResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(DTO_CONVERSION, "ScentRev MCP profile could not be converted to ScentRevFragranceProfileResponse.");
        }
        if (profile == null || profile.identity() == null || isBlank(profile.identity().name())
                || isBlank(profile.identity().brandSlug()) || isBlank(profile.identity().fragranceSlug())) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP profile identity is missing its name, brand slug or fragrance slug.");
        }
        return profile;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ScentRevMcpClientException sdkFailure(RuntimeException exception, boolean initializing) {
        return sdkFailure(exception, initializing, PROFILE_TOOL);
    }

    private static ScentRevMcpClientException sdkFailure(RuntimeException exception, boolean initializing, String tool) {
        String phase = initializing ? " during initialization." : " during " + tool + ".";
        // Classify SDK diagnostics; never retain raw messages, headers, bodies or causes.
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof McpHttpClientTransportAuthorizationException authentication) {
                int status = authentication.getResponseInfo().statusCode();
                return new ScentRevMcpClientException(AUTHENTICATION, status,
                        "ScentRev MCP authentication failed (HTTP " + status + ")" + phase);
            }
            if (!initializing && cause instanceof McpError mcpError && mcpError.getJsonRpcError() != null) {
                int code = mcpError.getJsonRpcError().code();
                String message = Objects.toString(mcpError.getJsonRpcError().message(), "").toLowerCase(Locale.ROOT);
                if (code == -32601 || (code == -32602
                        && (message.startsWith("unknown tool") || message.startsWith("tool not found")))) {
                    return failure(TOOL_NOT_FOUND, "ScentRev MCP " + tool + " tool was not found.");
                }
            }
        }
        cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof McpTransportException || cause instanceof IOException
                    || cause instanceof TimeoutException) {
                return failure(TRANSPORT, "ScentRev MCP transport failed" + phase);
            }
        }
        return failure(initializing ? INITIALIZATION : TOOL_CALL,
                initializing ? "ScentRev MCP initialization failed." : "ScentRev MCP " + tool + " call failed.");
    }

    private static ScentRevMcpClientException failure(FailureType type, String message) {
        return new ScentRevMcpClientException(type, null, message);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (sdkClient != null) {
            try {
                sdkClient.close();
            } catch (RuntimeException exception) {
                throw failure(TRANSPORT, "ScentRev MCP client resources could not be closed.");
            } finally {
                sdkClient = null;
            }
        }
    }
}
