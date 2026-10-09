package com.perfume.scentrev.curated;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.net.http.HttpRequest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevIdentity;
import com.perfume.scentrev.dto.ScentRevSearchResponse;
import com.perfume.scentrev.dto.ScentRevSearchResponse.Fragrance;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Six identities, two searches, and at most two identity follow-ups. No profiles, DB, or source writes. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.curated-deep-resolution-live-test", matches = "true")
class ScentRevCuratedDeepResolutionSmokeTests {
    static final int MAX_TOOL_CALLS = 10;
    private static final List<IdentityTarget> IDENTITY_TARGETS = List.of(
            new IdentityTarget("curated-035", "Hermès", "5002fec8-8aed-4c74-835f-3959edfcf645"),
            new IdentityTarget("curated-067", "Van Cleef & Arpels", "54a7bf82-0a09-4458-9cb3-22d754e1264b"),
            new IdentityTarget("curated-108", "Abercrombie & Fitch", "35645322-bb48-4b34-a06c-c57741b2284a"),
            new IdentityTarget("curated-111", "Ed Hardy", "c48f3d19-2603-4ed7-959b-026260b5e691"),
            new IdentityTarget("curated-147", "Chloé", "91bed0a1-4296-4304-ac31-148d1aa75a23"),
            new IdentityTarget("curated-161", "D.S. & Durga", "ef6f9492-fbb5-4f41-a721-f3cede69538f"));
    private static final List<SearchTarget> SEARCH_TARGETS = List.of(
            new SearchTarget("curated-110", "Dsquared2", "Original Wood", "Dsquared2"),
            new SearchTarget("curated-133", "미카로카", "Lady Muscat", "MIKA LOKKA"));

    @Test
    void reportsFinalProviderIdentitiesWithAtMostTenToolCalls() {
        var properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));
        var mapper = new ObjectMapper();
        try (var client = new DiagnosticClient(createSdkClient(properties, mapper), mapper)) {
            client.initialize();
            assertThat(report(client, System.out)).as("Diagnostic lookups reporting errors").isZero();
        }
    }

    static int report(DiagnosticClient client, PrintStream out) {
        int failures = 0;
        out.println("Final ScentRev identity diagnostic: maximum 10 MCP tool calls; no mapping is approved.");
        for (var target : IDENTITY_TARGETS) {
            out.printf("%n[IDENTITY] %s [%s]%nrequested_public_id = %s%n%n",
                    target.displayName(), target.parentKey(), target.publicId());
            try {
                printIdentity(client.getIdentity(target.publicId()), out);
            } catch (DiagnosticException error) {
                failures++;
                printError(error, out);
            }
        }
        for (var target : SEARCH_TARGETS) {
            out.printf("%n[SEARCH] %s [%s]%nquery = %s%n%n",
                    target.displayName(), target.parentKey(), target.query());
            try {
                var result = client.searchFragrances(target.query()); // One page only.
                for (int index = 0; index < result.results().size(); index++) {
                    var row = result.results().get(index);
                    out.printf("RESULT %d%n  fragrance_name = %s%n  fragrance_slug = %s%n  public_id = %s%n%n",
                            index + 1, available(row.name()), available(row.fragranceSlug()), available(row.publicId()));
                }
                var usable = result.results().stream().filter(row -> clearlyMatches(row, target)).toList();
                if (usable.size() != 1) {
                    out.println("NO_USABLE_RESULT");
                    continue;
                }
                out.println("FOLLOW_UP_IDENTITY");
                printIdentity(client.getIdentity(usable.get(0).publicId()), out); // At most one per search target.
            } catch (DiagnosticException error) {
                failures++;
                printError(error, out);
            }
        }
        out.printf("%nMCP tool calls attempted = %d (maximum %d)%n", client.calls(), MAX_TOOL_CALLS);
        return failures;
    }

    private static boolean clearlyMatches(Fragrance row, SearchTarget target) {
        if (!normalize(row.name()).equals(normalize(target.query()))) { return false; }
        String intended = normalize(target.intendedBrand());
        if (row.brandName() != null && !row.brandName().isBlank()) {
            return normalize(row.brandName()).equals(intended);
        }
        if (row.brandSlug() != null && !row.brandSlug().isBlank()) {
            return normalize(row.brandSlug()).replace('-', ' ').equals(intended);
        }
        // Optional brand metadata may be absent. Require the observed fragrance slug to carry the brand prefix.
        return normalize(row.fragranceSlug()).startsWith(intended.replace(' ', '-') + "-");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void printIdentity(ScentRevIdentity identity, PrintStream out) {
        out.printf("fragrance_name = %s%nfragrance_slug = %s%npublic_id = %s%nbrand_name = %s%nbrand_slug = %s%n",
                available(identity.name()), available(identity.fragranceSlug()), available(identity.publicId()),
                available(identity.brandName()), available(identity.brandSlug()));
    }

    private static void printError(DiagnosticException error, PrintStream out) {
        out.printf("ERROR%n  error = %s%n  message = %s%n", error.code, error.getMessage());
    }

    private static String available(String value) {
        return value == null || value.isBlank() ? "<unavailable>" : value;
    }

    private record IdentityTarget(String parentKey, String displayName, String publicId) { }
    private record SearchTarget(String parentKey, String displayName, String query, String intendedBrand) { }

    // No Java get_identity method exists in the production client. This test-only SDK path uses the
    // inspected MCP public_id argument and existing DTOs, without introducing a production API.
    static final class DiagnosticClient implements AutoCloseable {
        private final McpSyncClient sdk;
        private final ObjectMapper mapper;
        private final ObjectReader reader;
        private int calls;

        DiagnosticClient(McpSyncClient sdk, ObjectMapper mapper) {
            this.sdk = sdk;
            this.mapper = mapper;
            this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        }

        void initialize() {
            try {
                if (sdk.initialize() == null) { throw new IllegalStateException(); }
            } catch (RuntimeException error) {
                throw new DiagnosticException("INITIALIZATION", "ScentRev MCP diagnostic initialization failed.");
            }
        }

        int calls() { return calls; }

        ScentRevIdentity getIdentity(String publicId) {
            try { UUID.fromString(publicId); }
            catch (IllegalArgumentException | NullPointerException error) {
                throw new DiagnosticException("INVALID_REQUEST", "Identity lookup requires a fragrance public UUID.");
            }
            var domain = invoke("get_identity", Map.of("public_id", publicId));
            var identity = domain.has("identity") ? domain.path("identity") : domain;
            requireText(identity, "name");
            for (String field : List.of("fragrance_slug", "public_id", "brand_name", "brand_slug")) {
                requireOptionalText(identity, field);
            }
            return convert(identity, ScentRevIdentity.class);
        }

        ScentRevSearchResponse searchFragrances(String query) {
            var domain = invoke("search_fragrances", Map.of("query", query, "limit", ScentRevMcpClient.SEARCH_PAGE_LIMIT));
            var results = domain.path("results");
            if (!results.isArray() || results.size() > ScentRevMcpClient.SEARCH_PAGE_LIMIT) {
                throw new DiagnosticException("INVALID_RESPONSE", "Search must return at most ten fragrance rows.");
            }
            for (var row : results) {
                for (String field : List.of("name", "fragrance_slug", "public_id")) { requireText(row, field); }
                for (String field : List.of("brand_name", "brand_slug")) {
                    requireOptionalText(row, field);
                }
            }
            return convert(domain, ScentRevSearchResponse.class);
        }

        private JsonNode invoke(String tool, Map<String, Object> arguments) {
            if (calls >= MAX_TOOL_CALLS) {
                throw new DiagnosticException("CALL_LIMIT", "Diagnostic cannot exceed ten MCP tool calls.");
            }
            calls++; // Reserve before invoking: failed requests consume a slot too; no retries.
            CallToolResult result;
            try { result = sdk.callTool(CallToolRequest.builder(tool).arguments(arguments).build()); }
            catch (RuntimeException error) {
                throw new DiagnosticException("TOOL_CALL", "ScentRev MCP " + tool + " invocation failed.");
            }
            if (result == null) { throw new DiagnosticException("INVALID_RESPONSE", "MCP returned no tool result."); }
            if (Boolean.TRUE.equals(result.isError())) {
                throw new DiagnosticException("TOOL_CALL", "ScentRev MCP " + tool + " reported isError=true.");
            }
            JsonNode domain = null;
            try {
                if (result.structuredContent() != null) {
                    domain = mapper.valueToTree(result.structuredContent());
                } else if (result.content() != null) {
                    for (var content : result.content()) {
                        if (!(content instanceof TextContent text) || !text.text().strip().startsWith("{")) { continue; }
                        try {
                            JsonNode candidate = reader.readTree(text.text());
                            if (candidate.isObject() && (candidate.hasNonNull("error")
                                    || (tool.equals("get_identity") && (candidate.has("name") || candidate.has("identity")))
                                    || (tool.equals("search_fragrances") && candidate.has("results")))) {
                                domain = candidate;
                                break;
                            }
                        } catch (JsonProcessingException ignored) { /* Try the next text block, never another request. */ }
                    }
                }
            } catch (IllegalArgumentException error) {
                throw new DiagnosticException("DTO_CONVERSION", "MCP diagnostic content could not be converted to JSON.");
            }
            if (domain == null || !domain.isObject()) {
                throw new DiagnosticException("INVALID_RESPONSE", "MCP diagnostic returned no supported JSON object.");
            }
            if (domain.hasNonNull("error")) {
                throw new DiagnosticException("TOOL_CALL", "ScentRev MCP " + tool + " returned an error payload.");
            }
            return domain;
        }

        private void requireText(JsonNode object, String field) {
            if (!object.path(field).isTextual() || object.path(field).asText().isBlank()) {
                throw new DiagnosticException("INVALID_RESPONSE", "MCP diagnostic has missing or invalid textual identity fields.");
            }
        }

        private void requireOptionalText(JsonNode object, String field) {
            if (object.hasNonNull(field) && !object.path(field).isTextual()) {
                throw new DiagnosticException("INVALID_RESPONSE", "MCP diagnostic has invalid textual identity fields.");
            }
        }

        private <T> T convert(JsonNode domain, Class<T> type) {
            try { return mapper.treeToValue(domain, type); }
            catch (JsonProcessingException | IllegalArgumentException error) {
                throw new DiagnosticException("DTO_CONVERSION", "MCP diagnostic could not be converted to the existing DTO.");
            }
        }

        @Override public void close() {
            try { sdk.close(); }
            catch (RuntimeException error) {
                throw new DiagnosticException("TRANSPORT", "MCP diagnostic resources could not be closed.");
            }
        }
    }

    static final class DiagnosticException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        DiagnosticException(String code, String safeMessage) { super(safeMessage); this.code = code; }
    }

    private static McpSyncClient createSdkClient(ScentRevClientProperties properties, ObjectMapper mapper) {
        // Use the same verified transport/authentication settings as the existing Java MCP client.
        var protocolMapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var transport = HttpClientStreamableHttpTransport.builder(properties.getBaseUrl())
                .endpoint("/mcp/").connectTimeout(Duration.ofSeconds(10))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + properties.getApiKey()))
                .jsonMapper(new JacksonMcpJsonMapper(protocolMapper)).resumableStreams(false)
                .openConnectionOnStartup(false).build();
        try {
            return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(30))
                    .clientInfo(Implementation.builder("perfume-scentrev-client", "0.0.1").build())
                    .capabilities(ClientCapabilities.builder().build()).enableCallToolSchemaCaching(false).build();
        } catch (RuntimeException error) {
            try { transport.close(); } catch (RuntimeException ignored) { }
            throw new DiagnosticException("INITIALIZATION", "MCP diagnostic transport could not be created.");
        }
    }
}
