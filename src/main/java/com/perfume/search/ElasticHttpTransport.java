package com.perfume.search;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.ConnectException;
import javax.net.ssl.SSLException;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.perfume.search.SearchDiagnostics.*;

/** Small REST boundary, avoiding Elasticsearch auto-configuration and repository scanning. */
final class ElasticHttpTransport implements ElasticTransport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ElasticHttpTransport.class);
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
        Stage stage = operation(method, path);
        int status = 0;
        try {
            var request = HttpRequest.newBuilder(options.uri.resolve(path)).timeout(timeout)
                    .header("Authorization", "ApiKey " + options.apiKey)
                    .header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            if (status == 404 && method.equals("GET") && path.startsWith("/_alias/")) {
                log.info("Catalog diagnostic: stage={} outcome=not_found http_status=404", stage);
                return mapper.createObjectNode();
            }
            if (status < 200 || status >= 300) {
                String type = null;
                try { type = mapper.readTree(response.body()).path("error").path("type").asText(); }
                catch (Exception ignored) { } // Error text and parser exceptions may contain document data.
                throw details(stage, Code.HTTP_STATUS, "Elasticsearch request failed", status, null, type);
            }
            try { return mapper.readTree(response.body()); }
            catch (Exception invalid) {
                throw details(stage, Code.INVALID_RESPONSE, "Elasticsearch request failed", status, null, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw report(log, stage, failure(stage, Code.REQUEST_INTERRUPTED, "Elasticsearch request interrupted"));
        } catch (HttpTimeoutException timeoutFailure) {
            throw report(log, stage, failure(stage, Code.REQUEST_TIMEOUT, "Elasticsearch request failed"));
        } catch (ConnectException unavailable) {
            throw report(log, stage, failure(stage, Code.CONNECTION_FAILED, "Elasticsearch request failed"));
        } catch (SSLException invalidTls) {
            throw report(log, stage, failure(stage, Code.TLS_FAILED, "Elasticsearch request failed"));
        } catch (java.io.IOException ioFailure) {
            throw report(log, stage, failure(stage, Code.HTTP_IO_FAILED, "Elasticsearch request failed"));
        } catch (Exception failure) {
            var safe = failure instanceof Failure known ? known
                    : details(stage, Code.OPERATION_FAILED, "Elasticsearch request failed", status, null, null);
            throw report(log, stage, safe);
        }
    }

    private static Stage operation(String method, String path) {
        // Classify a local route, then discard it. No host, path, query parameter or header is logged.
        String route = path.split("\\?", 2)[0];
        if (route.startsWith("/_alias/")) return Stage.ALIAS_LOOKUP;
        if (route.equals("/_aliases")) return Stage.ALIAS_SWAP;
        if (route.endsWith("/_bulk")) return Stage.BULK_INDEX;
        if (route.endsWith("/_refresh")) return Stage.INDEX_REFRESH;
        if (route.endsWith("/_count")) return Stage.COUNT_VERIFY;
        if (route.endsWith("/_search")) return Stage.SEARCH;
        return method.equals("PUT") ? Stage.INDEX_CREATE : Stage.HTTP_REQUEST;
    }
}
