package com.perfume.search;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small REST boundary, avoiding Elasticsearch auto-configuration and repository scanning. */
final class ElasticHttpTransport implements ElasticTransport {
    private final ElasticsearchOptions options;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;

    ElasticHttpTransport(ElasticsearchOptions options, ObjectMapper mapper, Duration timeout) {
        this(options, mapper, timeout, client(options));
    }

    ElasticHttpTransport(ElasticsearchOptions options, ObjectMapper mapper, Duration timeout, HttpClient client) {
        this.options = options; this.mapper = mapper; this.timeout = timeout; this.client = client;
    }

    private static HttpClient client(ElasticsearchOptions options) {
        try {
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (var input = Files.newInputStream(options.ca)) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) throw new IllegalArgumentException();
                int i = 0;
                for (var certificate : certificates) store.setCertificateEntry("ca-" + i++, certificate);
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, trust.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofMillis(300))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (Exception invalid) { throw new IllegalArgumentException("Cannot load Elasticsearch CA certificate"); }
    }

    @Override
    public JsonNode request(String method, String path, String body) {
        try {
            var request = HttpRequest.newBuilder(options.uri.resolve(path)).timeout(timeout)
                    .header("Authorization", "ApiKey " + options.apiKey)
                    .header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 && method.equals("GET") && path.startsWith("/_alias/")) {
                return mapper.createObjectNode();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException();
            return mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Elasticsearch request interrupted");
        } catch (Exception failure) {
            // Never include response bodies (documents), credentials, URLs or the raw transport exception.
            throw new IllegalStateException("Elasticsearch request failed");
        }
    }
}
