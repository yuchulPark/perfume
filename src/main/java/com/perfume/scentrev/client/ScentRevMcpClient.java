package com.perfume.scentrev.client;

import static com.perfume.scentrev.client.ScentRevMcpClientException.FailureType.*;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevBrandListResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevNotePyramid;
import com.perfume.scentrev.dto.ScentRevSearchResponse;

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

    private static final Logger log = LoggerFactory.getLogger(ScentRevMcpClient.class);
    private static final int MAX_DIAGNOSTIC_LENGTH = 512;
    private static final Set<String> RATE_LIMIT_CODES = Set.of("rate_limit", "rate_limited", "rate_limit_exceeded",
            "too_many_requests", "quota_exceeded", "quota_exhausted", "429");
    private static final String PROFILE_TOOL = "get_fragrance_profile";
    private static final String WEAR_TOOL = "get_wear_summary";
    public static final List<String> PROFILE_SECTIONS = List.of("identity", "performance", "appreciation", "notes",
            "note_pyramid", "accords", "perfumers", "pros_cons", "reminds_of", "price_value");
    private static final String USER_SEARCH_TOOL = "search_fragrances";
    private static final String SEARCH_TOOL = "search_fragrances_filtered";
    private static final String BRANDS_TOOL = "list_brands";
    public static final int SEARCH_PAGE_LIMIT = 10;
    public static final int BRAND_PAGE_LIMIT = 10;

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
                            "sections", PROFILE_SECTIONS,
                            "include_perfumer_portfolio", false)).build());
        } catch (RuntimeException exception) {
            throw sdkFailure(exception, false);
        }
        return extractProfile(result);
    }

    /** Optional wear fallback, using the same session/authentication and immediate-stop error policy. */
    public synchronized JsonNode getWearSummary(String fragranceSlug) {
        if (closed) { throw failure(CLOSED, "ScentRev MCP client is closed."); }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(CONFIGURATION, "ScentRev MCP requires a nonblank SCENTREV_API_KEY.");
        }
        if (fragranceSlug == null || fragranceSlug.isBlank()) {
            throw failure(INVALID_REQUEST, "ScentRev MCP requires a nonblank fragrance slug.");
        }
        initializeIfNeeded();
        CallToolResult result;
        try {
            result = sdkClient.callTool(CallToolRequest.builder(WEAR_TOOL)
                    .arguments(Map.of("fragrance_slug", fragranceSlug)).build());
        } catch (RuntimeException exception) { throw sdkFailure(exception, false, WEAR_TOOL); }
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw toolFailure(WEAR_TOOL, errorDiagnostic(result), "ScentRev MCP get_wear_summary returned no result or isError=true.");
        }
        JsonNode domain = null;
        if (result.structuredContent() != null) {
            try { domain = objectMapper.valueToTree(result.structuredContent()); }
            catch (IllegalArgumentException exception) { throw failure(DTO_CONVERSION, "ScentRev MCP wear content is invalid JSON."); }
        } else if (result.content() != null) {
            for (var content : result.content()) {
                if (!(content instanceof TextContent text)) { continue; }
                try {
                    JsonNode candidate = domainReader.readTree(text.text());
                    if (candidate != null && candidate.isObject() && (candidate.has("fragrance_slug")
                            || candidate.has("public_id") || candidate.hasNonNull("error"))) { domain = candidate; break; }
                } catch (JsonProcessingException ignored) { }
            }
        }
        if (domain == null || !domain.isObject()) { throw failure(INVALID_RESPONSE, "ScentRev MCP returned no wear summary object."); }
        if (domain.hasNonNull("error")) {
            String code = domain.path("error").isTextual() ? domain.path("error").asText() : domain.path("error").path("code").asText();
            if ("tool_not_found".equals(code) || "unknown_tool".equals(code)) {
                throw failure(TOOL_NOT_FOUND, "ScentRev MCP get_wear_summary returned an error payload.");
            }
            throw toolFailure(WEAR_TOOL, domain, "ScentRev MCP get_wear_summary returned an error payload.");
        }
        return domain.deepCopy();
    }

    /** One name/brand/slug lookup page. The provider's parameter is limit, not result_limit. */
    public synchronized ScentRevSearchResponse searchFragrances(String query, int limit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.codePointCount(0, normalized.length()) < 2 || limit < 1 || limit > 10) {
            throw failure(INVALID_REQUEST, "ScentRev search requires at least 2 query characters and a limit from 1 to 10.");
        }
        if (closed) { throw failure(CLOSED, "ScentRev MCP client is closed."); }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(CONFIGURATION, "ScentRev MCP requires a nonblank SCENTREV_API_KEY.");
        }
        initializeIfNeeded();
        CallToolResult result;
        try {
            result = sdkClient.callTool(CallToolRequest.builder(USER_SEARCH_TOOL)
                    .arguments(Map.of("query", normalized, "limit", limit)).build());
        } catch (RuntimeException exception) {
            throw sdkFailure(exception, false, USER_SEARCH_TOOL);
        }
        return extractUserSearch(result);
    }

    private ScentRevSearchResponse extractUserSearch(CallToolResult result) {
        if (result == null) { throw failure(INVALID_RESPONSE, "ScentRev MCP returned no search result."); }
        if (Boolean.TRUE.equals(result.isError())) {
            throw toolFailure(USER_SEARCH_TOOL, errorDiagnostic(result), "ScentRev MCP search_fragrances reported isError=true.");
        }
        if (result.structuredContent() != null) {
            JsonNode domain;
            try { domain = objectMapper.valueToTree(result.structuredContent()); }
            catch (IllegalArgumentException exception) {
                throw failure(DTO_CONVERSION, "ScentRev MCP search content could not be converted to JSON.");
            }
            return convertUserSearch(domain);
        }
        for (var content : result.content()) {
            if (!(content instanceof TextContent textContent)) { continue; }
            String text = textContent.text().strip();
            if (!text.startsWith("{")) { continue; }
            JsonNode domain;
            try { domain = domainReader.readTree(text); }
            catch (JsonProcessingException exception) { continue; }
            if (domain.isObject() && (domain.has("results") || domain.hasNonNull("error"))) {
                return convertUserSearch(domain);
            }
        }
        throw failure(INVALID_RESPONSE, "ScentRev MCP returned no search in structured content or valid textual JSON.");
    }

    private ScentRevSearchResponse convertUserSearch(JsonNode domain) {
        if (domain != null && domain.isObject() && domain.hasNonNull("error")) {
            JsonNode error = domain.path("error");
            String code = error.isTextual() ? error.asText() : error.path("code").asText();
            if ("tool_not_found".equals(code) || "unknown_tool".equals(code)) {
                throw failure(TOOL_NOT_FOUND, "ScentRev MCP search_fragrances tool was not found.");
            }
            throw toolFailure(USER_SEARCH_TOOL, domain, "ScentRev MCP search_fragrances returned an error payload.");
        }
        if (domain == null || !domain.isObject() || !domain.path("results").isArray()) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP search must contain a results array.");
        }
        for (JsonNode row : domain.path("results")) {
            for (String field : List.of("fragrance_slug", "public_id", "name")) {
                if (!row.path(field).isTextual() || row.path(field).asText().isBlank()) {
                    throw failure(INVALID_RESPONSE, "ScentRev MCP search candidate is missing a required textual identifier or name.");
                }
            }
            for (String field : List.of("brand_name", "brand_slug")) {
                if (row.hasNonNull(field) && !row.path(field).isTextual()) {
                    throw failure(INVALID_RESPONSE, "ScentRev MCP search candidate has invalid brand metadata.");
                }
            }
        }
        try { return objectMapper.treeToValue(domain, ScentRevSearchResponse.class); }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(DTO_CONVERSION, "ScentRev MCP search could not be converted to ScentRevSearchResponse.");
        }
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
            throw toolFailure(SEARCH_TOOL, errorDiagnostic(result), "ScentRev MCP search_fragrances_filtered reported isError=true.");
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
            if (domain.isObject() && (domain.has("results") || domain.hasNonNull("error")
                    || diagnosticFailureType(domain) != TOOL_CALL)) {
                return convertSearch(domain);
            }
        }
        throw failure(INVALID_RESPONSE, "ScentRev MCP returned no filtered search in structured content or valid textual JSON.");
    }

    private ScentRevFilteredSearchResponse convertSearch(JsonNode domain) {
        // An explicit restriction is never an empty/successful page, even when isError is false.
        if (domain != null && domain.isObject() && diagnosticFailureType(domain) != TOOL_CALL) {
            throw toolFailure(SEARCH_TOOL, domain, "ScentRev MCP search_fragrances_filtered returned an error payload.");
        }
        if (domain != null && domain.isObject() && domain.hasNonNull("error")) {
            String code = domain.path("error").path("code").asText();
            if ("tool_not_found".equals(code) || "unknown_tool".equals(code)) {
                throw failure(TOOL_NOT_FOUND, "ScentRev MCP search_fragrances_filtered tool was not found.");
            }
            throw toolFailure(SEARCH_TOOL, domain, "ScentRev MCP search_fragrances_filtered returned an error payload.");
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

    /** List canonical brands without a query filter or the unbranded/unknown bucket. */
    public synchronized ScentRevBrandListResponse listBrands(int resultOffset) {
        if (closed) {
            throw failure(CLOSED, "ScentRev MCP client is closed.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(CONFIGURATION, "ScentRev MCP requires a nonblank SCENTREV_API_KEY.");
        }
        if (resultOffset < 0) {
            throw failure(INVALID_REQUEST, "ScentRev MCP brand listing requires a nonnegative offset.");
        }
        initializeIfNeeded();
        CallToolResult result;
        try {
            result = sdkClient.callTool(CallToolRequest.builder(BRANDS_TOOL)
                    .arguments(Map.of("result_limit", BRAND_PAGE_LIMIT, "result_offset", resultOffset,
                            "include_unbranded", false)).build());
        } catch (RuntimeException exception) {
            throw sdkFailure(exception, false, BRANDS_TOOL);
        }
        return extractBrands(result);
    }

    /** Targeted resolution only: exactly one queried page, with no cursor or pagination fallback. */
    public synchronized ScentRevBrandListResponse searchBrands(String query, int limit) {
        if (closed) { throw failure(CLOSED, "ScentRev MCP client is closed."); }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw failure(CONFIGURATION, "ScentRev MCP requires a nonblank SCENTREV_API_KEY.");
        }
        if (query == null || query.isBlank() || limit < 1 || limit > BRAND_PAGE_LIMIT) {
            throw failure(INVALID_REQUEST, "Targeted brand resolution requires a query and a limit between 1 and 10.");
        }
        initializeIfNeeded();
        CallToolResult result;
        try {
            result = sdkClient.callTool(CallToolRequest.builder(BRANDS_TOOL)
                    .arguments(Map.of("query", query, "result_limit", limit, "result_offset", 0,
                            "include_unbranded", false)).build());
        } catch (RuntimeException exception) {
            throw sdkFailure(exception, false, BRANDS_TOOL);
        }
        return extractBrands(result, true);
    }

    private ScentRevBrandListResponse extractBrands(CallToolResult result) { return extractBrands(result, false); }

    private ScentRevBrandListResponse extractBrands(CallToolResult result, boolean targeted) {
        if (result == null) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP returned no brand-list tool result.");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw toolFailure(BRANDS_TOOL, errorDiagnostic(result), "ScentRev MCP list_brands reported isError=true.");
        }
        if (result.structuredContent() != null) {
            JsonNode domain;
            try {
                domain = objectMapper.valueToTree(result.structuredContent());
            } catch (IllegalArgumentException exception) {
                throw failure(DTO_CONVERSION, "ScentRev MCP brand-list structured content could not be converted to JSON.");
            }
            return convertBrands(domain, targeted);
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
            if (domain.isObject() && (domain.has("brands") || domain.hasNonNull("error"))) {
                return convertBrands(domain, targeted);
            }
        }
        throw failure(INVALID_RESPONSE, "ScentRev MCP returned no brand list in structured content or valid textual JSON.");
    }

    private ScentRevBrandListResponse convertBrands(JsonNode domain, boolean targeted) {
        if (domain != null && domain.isObject() && domain.hasNonNull("error")) {
            JsonNode error = domain.path("error");
            String code = error.isTextual() ? error.asText() : error.path("code").asText();
            if (targeted && "no_results".equals(code)) {
                return new ScentRevBrandListResponse(List.of(), false, 0, BRAND_PAGE_LIMIT, 0);
            }
            if ("tool_not_found".equals(code) || "unknown_tool".equals(code)) {
                throw failure(TOOL_NOT_FOUND, "ScentRev MCP list_brands tool was not found.");
            }
            throw toolFailure(BRANDS_TOOL, domain, "ScentRev MCP list_brands returned an error payload.");
        }
        if (domain == null || !domain.isObject() || !domain.path("brands").isArray()) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP brand list must contain a brands array.");
        }
        for (String field : List.of("offset", "limit", "total_returned")) {
            JsonNode value = domain.path(field);
            if (!value.isMissingNode() && !value.isNull()
                    && (!value.isIntegralNumber() || !value.canConvertToInt())) {
                throw failure(INVALID_RESPONSE, "ScentRev MCP brand list has invalid numeric pagination metadata.");
            }
        }
        JsonNode truncated = domain.path("truncated");
        if (!truncated.isMissingNode() && !truncated.isNull() && !truncated.isBoolean()) {
            throw failure(INVALID_RESPONSE, "ScentRev MCP brand list has invalid boolean pagination metadata.");
        }
        try {
            return objectMapper.treeToValue(domain, ScentRevBrandListResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(DTO_CONVERSION, "ScentRev MCP brand list could not be converted to ScentRevBrandListResponse.");
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
            throw toolFailure(PROFILE_TOOL, errorDiagnostic(result), "ScentRev MCP get_fragrance_profile reported isError=true.");
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
            throw toolFailure(PROFILE_TOOL, domain, "ScentRev MCP get_fragrance_profile returned an error payload.");
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
        if (profile.notePyramid() == null && profile.notes() != null && profile.notes().isObject()
                && (profile.notes().has("top") || profile.notes().has("middle") || profile.notes().has("base"))) {
            try { profile = profile.withNotePyramid(objectMapper.treeToValue(profile.notes(), ScentRevNotePyramid.class)); }
            catch (JsonProcessingException exception) { throw failure(DTO_CONVERSION, "ScentRev MCP layered notes could not be converted."); }
        }
        return profile.withRawResponse(domain);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ScentRevMcpClientException sdkFailure(RuntimeException exception, boolean initializing) {
        return sdkFailure(exception, initializing, PROFILE_TOOL);
    }

    private ScentRevMcpClientException sdkFailure(RuntimeException exception, boolean initializing, String tool) {
        String phase = initializing ? " during initialization." : " during " + tool + ".";
        // Preserve authentication/tool-not-found handling and inspect explicit restriction/schema diagnostics.
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
                JsonNode diagnostic = protocolDiagnostic(mcpError);
                FailureType type = diagnosticFailureType(diagnostic);
                if (type != TOOL_CALL) {
                    Long retryAfterMs = type == RATE_LIMIT ? retryAfterMs(diagnostic) : null;
                    logToolFailure(tool, exception, type, retryAfterMs);
                    return diagnosticFailure(type, tool, retryAfterMs);
                }
            }
            // SDK 2.0.1 drops HTTP headers/body for this 4xx error; do not invent a Retry-After value.
            if (cause instanceof McpTransportException
                    && "Invalid request. Status code: 429".equals(cause.getMessage())) {
                logToolFailure(tool, exception, RATE_LIMIT, null);
                return new ScentRevMcpClientException(RATE_LIMIT, 429,
                        "ScentRev MCP request limit exceeded (HTTP 429)" + phase);
            }
        }
        cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof McpTransportException || cause instanceof IOException
                    || cause instanceof TimeoutException) {
                return failure(TRANSPORT, "ScentRev MCP transport failed" + phase);
            }
        }
        if (!initializing) { logToolFailure(tool, exception, TOOL_CALL, null); }
        return failure(initializing ? INITIALIZATION : TOOL_CALL,
                initializing ? "ScentRev MCP initialization failed." : "ScentRev MCP " + tool + " call failed.");
    }

    private JsonNode errorDiagnostic(CallToolResult result) {
        // Read diagnostics without converting an error result into successful domain data.
        if (result != null && result.structuredContent() != null) {
            try {
                JsonNode domain = objectMapper.valueToTree(result.structuredContent());
                if (hasErrorDiagnostic(domain)) { return domain; }
            } catch (IllegalArgumentException ignored) { }
        }
        String plainMessage = null;
        if (result != null && result.content() != null) {
            for (var content : result.content()) {
                if (!(content instanceof TextContent text) || text.text() == null) { continue; }
                String value = text.text().strip();
                if (value.startsWith("{")) {
                    try {
                        JsonNode domain = domainReader.readTree(value);
                        if (hasErrorDiagnostic(domain)) { return domain; }
                    } catch (JsonProcessingException ignored) { }
                } else if (plainMessage == null && !value.startsWith("[") && !value.startsWith("<")) {
                    plainMessage = value;
                }
            }
        }
        return objectMapper.createObjectNode().put("message", plainMessage);
    }

    private ScentRevMcpClientException toolFailure(String tool, JsonNode domain, String fallbackMessage) {
        FailureType type = diagnosticFailureType(domain);
        logToolFailure(tool, domain);
        return type == TOOL_CALL ? failure(TOOL_CALL, fallbackMessage)
                : diagnosticFailure(type, tool, type == RATE_LIMIT ? retryAfterMs(domain) : null);
    }

    private static ScentRevMcpClientException diagnosticFailure(FailureType type, String tool, Long retryAfterMs) {
        String message = type == SERVER_OUTPUT_SCHEMA
                ? "ScentRev MCP " + tool + " server outputSchema validation failed; the provider must fix its response/schema contract."
                : "ScentRev MCP " + tool + " request limit or API quota exceeded."
                        + (retryAfterMs == null ? "" : " Provider retry hint: " + retryAfterMs + " ms.")
                        + " No automatic retry was made.";
        return new ScentRevMcpClientException(type, null, message, retryAfterMs);
    }

    private static FailureType diagnosticFailureType(JsonNode domain) {
        JsonNode error = domain.hasNonNull("error") ? domain.path("error") : domain;
        String code = diagnosticValue(error.isValueNode() ? error : error.path("code"));
        // Schema field names (quota/retry_after_ms) alone are not evidence of a request limit.
        if (isOutputValidationMessage(diagnosticValue(domain.path("message")))
                || isOutputValidationMessage(diagnosticValue(error.path("message")))) {
            return SERVER_OUTPUT_SCHEMA;
        }
        return code != null && RATE_LIMIT_CODES.contains(code.toLowerCase(Locale.ROOT)) ? RATE_LIMIT : TOOL_CALL;
    }

    private static boolean isOutputValidationMessage(String message) {
        return message != null && message.strip().toLowerCase(Locale.ROOT).startsWith("output validation error:");
    }

    private static Long retryAfterMs(JsonNode domain) {
        JsonNode error = domain.path("error");
        JsonNode retry = error.has("retry_after_ms") ? error.path("retry_after_ms") : domain.path("retry_after_ms");
        return retry.isIntegralNumber() && retry.canConvertToLong() && retry.longValue() >= 0 ? retry.longValue() : null;
    }

    private JsonNode protocolDiagnostic(McpError exception) {
        var diagnostic = objectMapper.createObjectNode();
        Object data = exception.getJsonRpcError().data();
        if (data instanceof Map<?, ?> || data instanceof JsonNode) {
            try {
                JsonNode candidate = objectMapper.valueToTree(data);
                if (candidate.isObject()) { diagnostic.setAll((com.fasterxml.jackson.databind.node.ObjectNode) candidate); }
            } catch (IllegalArgumentException ignored) { }
        }
        if (!diagnostic.hasNonNull("code")) { diagnostic.put("code", exception.getJsonRpcError().code()); }
        if (!diagnostic.hasNonNull("message") || isOutputValidationMessage(exception.getJsonRpcError().message())) {
            diagnostic.put("message", exception.getJsonRpcError().message());
        }
        return diagnostic;
    }

    private static boolean hasErrorDiagnostic(JsonNode domain) {
        return domain != null && domain.isObject()
                && (domain.hasNonNull("error") || domain.hasNonNull("code") || domain.hasNonNull("message"));
    }

    private void logToolFailure(String tool, JsonNode domain) {
        JsonNode error = domain.hasNonNull("error") ? domain.path("error") : domain;
        String code = diagnosticValue(error.isValueNode() ? error : error.path("code"));
        String message = diagnosticValue(error.path("message"));
        if (message == null) { message = diagnosticValue(domain.path("message")); }
        FailureType type = diagnosticFailureType(domain);
        log.warn("ScentRev MCP " + type + " tool={} serverCode={} serverMessage={} retryAfterMs={}",
                tool, safeDiagnostic(code), safeDiagnostic(message), type == RATE_LIMIT ? retryAfterMs(domain) : null);
    }

    private static String diagnosticValue(JsonNode value) {
        return value.isTextual() || value.isNumber() ? value.asText() : null;
    }

    private void logToolFailure(String tool, RuntimeException exception, FailureType type, Long retryAfterMs) {
        String code = null, message = null;
        StringBuilder causes = new StringBuilder();
        // Bound traversal even for cyclic causes; never log Throwable/toString or JSON-RPC data.
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (depth > 0) { causes.append(" -> "); }
            String causeMessage = cause.getMessage();
            // Throwable(cause) messages can contain McpError.toString(), including private RPC data.
            if (cause.getCause() != null && causeMessage != null
                    && causeMessage.startsWith(cause.getCause().getClass().getName())) {
                causeMessage = "(see nested cause)";
            }
            causes.append(cause.getClass().getName()).append(": ").append(safeDiagnostic(causeMessage));
            if (code == null && cause instanceof McpError mcpError && mcpError.getJsonRpcError() != null) {
                code = Integer.toString(mcpError.getJsonRpcError().code());
                message = mcpError.getJsonRpcError().message();
            }
        }
        log.warn("ScentRev MCP " + type + " tool={} serverCode={} serverMessage={} causes={} retryAfterMs={}",
                tool, safeDiagnostic(code), safeDiagnostic(message), causes.toString(), retryAfterMs);
    }

    private String safeDiagnostic(String value) {
        if (value == null || value.isBlank()) { return "(not supplied)"; }
        String safe = value;
        if (!isBlank(properties.getApiKey())) { safe = safe.replace(properties.getApiKey(), "[REDACTED]"); }
        safe = safe.replaceAll("(?i)\\b(Bearer|Basic)\\s+[^\\s\"',;<>}\\]]+", "$1 [REDACTED]")
                .replaceAll("(?i)(\\b(?:authorization|proxy-authorization|(?:set-)?cookie)\\b[\"']?\\s*[:=]\\s*)"
                        + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n]*)", "$1[REDACTED]")
                .replaceAll("(?i)(\\b(?:x-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|password)"
                        + "\\b[\"']?\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;}&]+)", "$1[REDACTED]")
                .replaceAll("(?i)(https?://)[^\\s/]+@", "$1[REDACTED]@")
                .replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").strip();
        // Redact before truncating so a credential crossing the boundary cannot leak a prefix.
        return safe.substring(0, Math.min(safe.length(), MAX_DIAGNOSTIC_LENGTH));
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
