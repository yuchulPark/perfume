package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.api.service.PerfumeQueryService;
import com.perfume.repository.*;

class ElasticHttpTransportTests {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(ints = {300, 5000})
    void clientConnectionLimitsAreIndependentForWebAndManualReindex(int connectMillis) throws Exception {
        var env = environment();
        // Reuse a local JDK trust anchor as a parsing fixture. No TLS connection or certificate installation.
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((java.security.KeyStore) null);
        var certificate = ((X509TrustManager) trust.getTrustManagers()[0]).getAcceptedIssuers()[0];
        Path ca = directory.resolve("ca.crt"); Files.write(ca, certificate.getEncoded());
        env.withProperty("perfume.elasticsearch.ca-file", ca.toString());
        var options = ElasticsearchOptions.from(env, false);
        var transport = connectMillis == 300
                ? new ElasticHttpTransport(options, new ObjectMapper(), Duration.ofMillis(1500))
                : new ElasticHttpTransport(options, new ObjectMapper(), Duration.ofSeconds(30), Duration.ofSeconds(5));
        var client = (HttpClient) ReflectionTestUtils.getField(transport, "client");
        assertThat(client.connectTimeout()).contains(Duration.ofMillis(connectMillis));
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test void webConfigurationKeepsDefaultConnectionLimitAnd1500msRequestLimit() throws Exception {
        try (var created = mockConstruction(ElasticHttpTransport.class, (mock, context) -> {
            assertThat(context.arguments()).hasSize(3); // Default constructor retains the 300ms connection limit.
            assertThat(context.arguments().get(2)).isEqualTo(Duration.ofMillis(1500));
        })) {
            var search = new ElasticsearchConfiguration().catalogSearch(
                    environment().withProperty("perfume.elasticsearch.enabled", "true"), new ObjectMapper());
            assertThat(search).isInstanceOf(ElasticsearchCatalogSearch.class);
            assertThat(created.constructed()).hasSize(1);
        }
    }

    @Test void manualRequestRetainsThirtySecondLimitAndMissingAliasRemainsNormal() throws Exception {
        var client = mock(HttpClient.class); var response = reply(404, "{}");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var transport = new ElasticHttpTransport(ElasticsearchOptions.from(environment(), false),
                new ObjectMapper(), Duration.ofSeconds(30), client);
        assertThat(transport.request("GET", "/_alias/perfumes-search", null).isEmpty()).isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(30));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void connectionAndRequestTimeoutsBothPreservePostgresFallbackAndCooldown(boolean connectionTimeout) throws Exception {
        var client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(connectionTimeout
                ? new java.net.http.HttpConnectTimeoutException("sensitive endpoint")
                : new java.net.http.HttpTimeoutException("sensitive endpoint"));
        var search = new ElasticsearchCatalogSearch(transport(client), new ObjectMapper(), "perfumes-search", Clock.systemUTC());
        var perfumes = mock(PerfumeRepository.class);
        when(perfumes.findCatalogPage("%aventus%", null, PageRequest.of(0, 20))).thenReturn(new PageImpl<>(java.util.List.of()));
        var service = new PerfumeQueryService(perfumes, mock(PerfumeNoteRepository.class),
                mock(PerfumeUnlayeredNoteRepository.class), mock(PerfumeAccordRepository.class), mock(PerfumePerfumerRepository.class),
                mock(PerfumeMetricRepository.class), mock(PerfumeOpinionRepository.class), mock(PerfumeSimilarityRepository.class), search);
        assertThat(service.list(0, 20, "Aventus", null).content()).isEmpty();
        assertThat(service.list(0, 20, "Aventus", null).content()).isEmpty();
        verify(perfumes, times(2)).findCatalogPage("%aventus%", null, PageRequest.of(0, 20));
        verify(client, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test void requestsUseApiKeyHttpsAndABoundedTimeout() throws Exception {
        var client = mock(HttpClient.class);
        var response = reply(200, "{\"hits\":{}}");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        transport(client).request("POST", "/perfumes-search/_search", "{}");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://elasticsearch:9200/perfumes-search/_search");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("ApiKey private-key==");
        assertThat(request.getValue().timeout()).contains(Duration.ofMillis(1500));
    }
    @ParameterizedTest @ValueSource(ints = {301, 401, 403, 404, 429, 500, 503})
    void errorsAreRedactedAndNeverReturnedAsEmptySearchSuccess(int status) throws Exception {
        var client = mock(HttpClient.class);
        var response = reply(status, "sensitive document or credential");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThatThrownBy(() -> transport(client).request("POST", "/perfumes-search/_search", "{}"))
                .hasMessage("Elasticsearch request failed").hasNoCause();
    }
    @Test void onlyAliasDiscoveryTreats404AsFirstIndex() throws Exception {
        var client = mock(HttpClient.class);
        var response = reply(404, "{}");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(transport(client).request("GET", "/_alias/perfumes-search", null).isEmpty()).isTrue();
        assertThatThrownBy(() -> transport(client).request("GET", "/perfumes-search/_count", null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void timeoutAndMalformedJsonAreFailures() throws Exception {
        var client = mock(HttpClient.class);
        var response = reply(200, "not json");
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpTimeoutException("sensitive endpoint"))
                .thenReturn(response);
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> transport(client).request("POST", "/perfumes-search/_search", "{}"))
                .hasMessage("Elasticsearch request failed").hasNoCause();
    }
    @Test void interruptedRequestsRestoreTheInterruptFlag() throws Exception {
        var client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> transport(client).request("POST", "/perfumes-search/_search", "{}"))
                    .hasMessage("Elasticsearch request interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    private ElasticHttpTransport transport(HttpClient client) throws Exception {
        return new ElasticHttpTransport(ElasticsearchOptions.from(environment(), false), new ObjectMapper(), Duration.ofMillis(1500), client);
    }
    private MockEnvironment environment() throws Exception {
        Path key = directory.resolve("api-key"); Files.writeString(key, "private-key==");
        return new MockEnvironment().withProperty("perfume.elasticsearch.url", "https://elasticsearch:9200")
                .withProperty("perfume.elasticsearch.api-key-file", key.toString()).withProperty("perfume.elasticsearch.ca-file", "unused");
    }
    @SuppressWarnings("unchecked") private HttpResponse<String> reply(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body); return response;
    }
}
