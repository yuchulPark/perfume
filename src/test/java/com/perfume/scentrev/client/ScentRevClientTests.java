package com.perfume.scentrev.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevClientException.FailureType;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevAccords;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** MockRestServiceServer replaces HTTP transport; no network, Spring context, or database is used. */
class ScentRevClientTests {

    private static final String PUBLIC_ID = "312329ca-0ad9-4a79-a586-2a4b815c18f7";
    private static final String ENDPOINT = "https://api.scentrev.com/rpc/get_fragrance_profile";
    private static final String TEST_KEY = "test-key";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScentRevClientProperties properties;
    private MockRestServiceServer server;
    private ScentRevClient client;

    @BeforeEach
    void setUp() {
        properties = new ScentRevClientProperties();
        properties.setApiKey(TEST_KEY);
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ScentRevClient(builder.build(), properties, objectMapper);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void postsExactPhase1RequestAndDeserializesExistingProfileDto() throws IOException {
        String response = fixture("rich-profile.json").replace("fragrance-aventus", PUBLIC_ID);
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {
                          "public_id": "312329ca-0ad9-4a79-a586-2a4b815c18f7",
                          "sections": ["identity", "perfumers", "notes", "accords"],
                          "include_perfumer_portfolio": false
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ScentRevFragranceProfileResponse profile = client.getFragranceProfile(PUBLIC_ID);

        assertThat(profile.identity().publicId()).isEqualTo(PUBLIC_ID);
        assertThat(profile.identity().name()).isEqualTo("Aventus");
        assertThat(profile.identity().brandSlug()).isEqualTo("creed");
        assertThat(profile.perfumers()).hasSize(2);
        assertThat(profile.notePyramid().top()).containsExactly("Pineapple", "Bergamot");
        assertThat(profile.notePyramid().middle()).containsExactly("Birch", "Jasmine");
        assertThat(profile.notePyramid().base()).containsExactly("Musk", "Oakmoss");
        assertThat(profile.accords().accords()).extracting(ScentRevAccords.Accord::name)
                .containsExactly("fruity", "smoky", "woody");
        assertThat(profile.accords().accords().get(1).percentage()).isEqualTo(68);
        assertThat(profile.accords().accords().get(1).score()).isEqualTo(new BigDecimal("0.68"));
    }

    @Test
    void deserializesSparseProfileWithoutRequiringOptionalSections() throws IOException {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(fixture("sparse-profile.json"), MediaType.APPLICATION_JSON));

        ScentRevFragranceProfileResponse profile = client.getFragranceProfile("fragrance-daring");

        assertThat(profile.identity().releaseYear()).isNull();
        assertThat(profile.perfumers()).isEmpty();
        assertThat(profile.notePyramid().top()).isEmpty();
        assertThat(profile.accords().accords()).isEmpty();
        assertThat(profile.priceValue()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void rejectsMissingApiKeyWithoutMakingRequest(String key) {
        properties.setApiKey(key);

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.CONFIGURATION);
        assertThat(failure.getStatusCode()).isNull();
        assertThat(failure).hasMessage("ScentRev API key is not configured").hasNoCause();
        // No HTTP expectation exists, so any attempted request would fail this test.
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void rejectsMissingPublicIdWithoutMakingRequest(String publicId) {
        assertThatIllegalArgumentException().isThrownBy(() -> client.getFragranceProfile(publicId))
                .withMessageContaining("publicId");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void reportsInvalidRequestWithSafeNestedProviderMessage(int status) {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":"invalid_param","message":"Unknown public_id","token":"other-secret"}}
                        """));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.INVALID_REQUEST);
        assertThat(failure.getStatusCode()).isEqualTo(status);
        assertThat(failure).hasMessageContaining("Unknown public_id").hasMessageContaining("HTTP " + status)
                .hasNoCause();
        assertThat(failure.getMessage()).doesNotContain("other-secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void reportsAuthenticationFailureWithoutExposingEchoedKeyOrUnrelatedFields(int status) throws IOException {
        String body = objectMapper.writeValueAsString(Map.of(
                "message", "Invalid test-key; Authorization: Bearer test-key",
                "api_key", TEST_KEY, "headers", Map.of("Authorization", "Bearer other-secret")));
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.AUTHENTICATION);
        assertThat(failure.getStatusCode()).isEqualTo(status);
        assertThat(failure).hasMessageContaining("authentication failed").hasMessageContaining("[REDACTED]")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain(TEST_KEY, "other-secret", "api_key");
    }

    @Test
    void reportsRateLimitWithOneRequestAndNoRetry() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Please wait. Bearer other-secret\nRate limit reached."));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.RATE_LIMIT);
        assertThat(failure.getStatusCode()).isEqualTo(429);
        assertThat(failure).hasMessageContaining("rate limit").hasMessageContaining("Please wait").hasNoCause();
        assertThat(failure.getMessage()).doesNotContain("other-secret", "\n");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void reportsServerFailureAndPreservesSafeProviderDetail(int status) {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"Temporary provider failure\"}"));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.SERVER);
        assertThat(failure.getStatusCode()).isEqualTo(status);
        assertThat(failure).hasMessageContaining("server failure").hasMessageContaining("Temporary provider failure")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204})
    void rejectsEmptySuccessResponseInsteadOfReturningNull(int status) {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status)));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.INVALID_RESPONSE);
        assertThat(failure.getStatusCode()).isEqualTo(status);
        assertThat(failure).hasMessageContaining("empty or missing identity");
    }

    @Test
    void rejectsSuccessErrorEnvelopeThatHasNoProfileIdentity() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"error\":{\"message\":\"Provider error test-key\"}}", MediaType.APPLICATION_JSON));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.INVALID_RESPONSE);
        assertThat(failure.getStatusCode()).isEqualTo(200);
        assertThat(failure.toString()).doesNotContain(TEST_KEY);
    }

    @Test
    void translatesMalformedJsonWithoutKeepingUnsafeConversionException() {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{bad-json test-key", MediaType.APPLICATION_JSON));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.INVALID_RESPONSE);
        assertThat(failure).hasMessageContaining("could not be read").hasNoCause();
        assertThat(failure.toString()).doesNotContain(TEST_KEY, "bad-json");
    }

    @Test
    void translatesTransportFailureWithoutKeepingUnsafeIOException() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(request -> {
            throw new IOException("Unsafe transport diagnostic: Bearer test-key");
        });

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.TRANSPORT);
        assertThat(failure.getStatusCode()).isNull();
        assertThat(failure).hasMessageContaining("could not be completed").hasNoCause();
        assertThat(failure.toString()).doesNotContain(TEST_KEY, "Unsafe transport diagnostic");
    }

    @Test
    void boundsProviderDiagnosticsAfterRedactingCredentials() throws IOException {
        String body = objectMapper.writeValueAsString(Map.of("message", "Invalid test-key " + "x".repeat(700)));
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body(body));

        ScentRevClientException failure = failure();

        String prefix = "ScentRev rejected the profile request (HTTP 400): ";
        assertThat(failure.getMessage()).startsWith(prefix).contains("[REDACTED]").doesNotContain(TEST_KEY);
        assertThat(failure.getMessage().length()).isEqualTo(prefix.length() + 512);
    }

    @Test
    void omitsOversizedProviderBodyInsteadOfRetainingTruncatedCredentials() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.TEXT_PLAIN).body("x".repeat(8190) + "test-key"));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.AUTHENTICATION);
        assertThat(failure).hasMessage("ScentRev authentication failed (HTTP 401)").hasNoCause();
    }

    @Test
    void reportsUnexpectedRedirectWithoutFollowingIt() {
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT)
                .location(java.net.URI.create("https://unexpected.example.test")));

        ScentRevClientException failure = failure();

        assertThat(failure.getFailureType()).isEqualTo(FailureType.INVALID_RESPONSE);
        assertThat(failure.getStatusCode()).isEqualTo(307);
    }

    private ScentRevClientException failure() {
        return catchThrowableOfType(ScentRevClientException.class, () -> client.getFragranceProfile(PUBLIC_ID));
    }

    private String fixture(String fileName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/scentrev/" + fileName)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
