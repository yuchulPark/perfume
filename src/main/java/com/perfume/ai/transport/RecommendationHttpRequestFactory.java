package com.perfume.ai.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Only recommendation-scoped requests use cancelable JDK exchanges; standalone search/batch stay unchanged. */
public final class RecommendationHttpRequestFactory implements ClientHttpRequestFactory {
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");
    private final ClientHttpRequestFactory ordinary;
    private final HttpClient client;
    private final Duration timeout;
    public RecommendationHttpRequestFactory(Duration connectTimeout, Duration readTimeout) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build(), readTimeout, ordinary(connectTimeout, readTimeout));
    }
    public RecommendationHttpRequestFactory(HttpClient client, Duration timeout, ClientHttpRequestFactory ordinary) {
        this.client = client; this.timeout = timeout; this.ordinary = ordinary;
    }
    private static ClientHttpRequestFactory ordinary(Duration connect, Duration read) {
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(connect); factory.setReadTimeout(read); return factory;
    }
    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        var scope = RecommendationCancellation.current();
        if (scope == null) return ordinary.createRequest(uri, method);
        return new ClientHttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();
            private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
            @Override public HttpMethod getMethod() { return method; }
            @Override public URI getURI() { return uri; }
            @Override public HttpHeaders getHeaders() { return headers; }
            @Override public java.io.OutputStream getBody() { return output; }
            @Override public java.util.Map<String, Object> getAttributes() { return attributes; }
            @Override public ClientHttpResponse execute() throws IOException {
                byte[] body = output.toByteArray();
                if (scope.isCancelled() || Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
                var request = HttpRequest.newBuilder(uri).timeout(timeout);
                headers.forEach((key, values) -> { if (!RESTRICTED.contains(key.toLowerCase(java.util.Locale.ROOT))) values.forEach(value -> request.header(key, value)); });
                request.method(method.name(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                // Both headers and the entire bounded body must finish inside the request's read limit.
                var subscriber = new BoundedBody(1_048_576);
                var future = client.sendAsync(request.build(), info -> { if (info.statusCode() < 200 || info.statusCode() >= 300) subscriber.discard(); return subscriber; });
                try (var registration = scope.onCancel(() -> { subscriber.cancel(); future.cancel(true); })) {
                    HttpResponse<byte[]> response;
                    try { response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
                    catch (InterruptedException interrupted) {
                        subscriber.cancel(); future.cancel(true); Thread.currentThread().interrupt(); throw new IOException("Request cancelled.");
                    } catch (TimeoutException expired) {
                        subscriber.cancel(); future.cancel(true); throw new HttpTimeoutException("Request deadline exceeded.");
                    } catch (CancellationException cancelled) { throw new IOException("Request cancelled."); }
                    catch (ExecutionException failed) {
                        if (failed.getCause() instanceof HttpTimeoutException expired) throw expired;
                        throw new IOException("HTTP exchange failed.");
                    }
                    var responseHeaders = new HttpHeaders(); response.headers().map().forEach(responseHeaders::put);
                    var input = new ByteArrayInputStream(response.body());
                    return new ClientHttpResponse() {
                        @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.statusCode()); }
                        @Override public String getStatusText() { return ""; }
                        @Override public HttpHeaders getHeaders() { return responseHeaders; }
                        @Override public InputStream getBody() { return input; }
                        @Override public void close() { try { input.close(); } catch (IOException ignored) { } }
                    };
                } catch (IOException failure) { throw failure; }
                catch (Exception failure) { subscriber.cancel(); future.cancel(true); throw new IOException("HTTP exchange failed."); }
            }
        };
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        private boolean cancelled;
        private boolean discard;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletableFuture<byte[]> getBody() { return body; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (discard) { subscription.cancel(); body.complete(new byte[0]); return; }
            if (this.subscription != null || cancelled) { subscription.cancel(); return; }
            this.subscription = subscription; subscription.request(1);
        }
        synchronized void discard() { discard = true; }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { cancel(); return; }
                var part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable failure) { body.completeExceptionally(new IOException("HTTP response incomplete.")); }
        @Override public synchronized void onComplete() { if (!cancelled) body.complete(bytes.toByteArray()); }
        synchronized void cancel() {
            cancelled = true; if (subscription != null) subscription.cancel();
            body.completeExceptionally(new IOException("HTTP response cancelled or too large."));
        }
    }
}
