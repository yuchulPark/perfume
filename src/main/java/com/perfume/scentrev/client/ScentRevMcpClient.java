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

/** One synchronous profile operation over a lazily initialized, reusable SDK client. */
public class ScentRevMcpClient implements AutoCloseable {

    private static final String PROFILE_TOOL = "get_fragrance_profile";

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
        String phase = initializing ? " during initialization." : " during get_fragrance_profile.";
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
                    return failure(TOOL_NOT_FOUND, "ScentRev MCP get_fragrance_profile tool was not found.");
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
                initializing ? "ScentRev MCP initialization failed." : "ScentRev MCP get_fragrance_profile call failed.");
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
