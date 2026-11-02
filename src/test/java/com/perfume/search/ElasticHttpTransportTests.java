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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;

class ElasticHttpTransportTests {
    @TempDir Path directory;
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
        Path key = directory.resolve("api-key"); Files.writeString(key, "private-key==");
        var env = new MockEnvironment().withProperty("perfume.elasticsearch.url", "https://elasticsearch:9200")
                .withProperty("perfume.elasticsearch.api-key-file", key.toString()).withProperty("perfume.elasticsearch.ca-file", "unused");
        return new ElasticHttpTransport(ElasticsearchOptions.from(env, false), new ObjectMapper(), Duration.ofMillis(1500), client);
    }
    @SuppressWarnings("unchecked") private HttpResponse<String> reply(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body); return response;
    }
}
