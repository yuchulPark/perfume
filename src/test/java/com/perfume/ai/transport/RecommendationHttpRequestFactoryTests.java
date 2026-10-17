package com.perfume.ai.transport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;

class RecommendationHttpRequestFactoryTests {
    @Test void cancellationCancelsTheActualExchangeAndWaiterExitsWithSafeIoFailure() throws Exception {
        var client = mock(HttpClient.class); var ordinary = mock(ClientHttpRequestFactory.class);
        var exchange = new CompletableFuture<HttpResponse<byte[]>>(); var entered = new CountDownLatch(1);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(call -> { entered.countDown(); return exchange; });
        var factory = new RecommendationHttpRequestFactory(client, Duration.ofSeconds(10), ordinary);
        var scope = new RecommendationCancellation(); var callers = Executors.newSingleThreadExecutor();
        try {
            var done = callers.submit(() -> {
                try (var binding = scope.bind()) { return catchThrowable(() -> factory.createRequest(URI.create("http://ollama:11434/api/chat"), HttpMethod.POST).execute()); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); scope.cancel();
            assertThat(done.get(2, TimeUnit.SECONDS)).isInstanceOf(IOException.class).hasMessageNotContaining("private-");
            assertThat(exchange.isCancelled()).isTrue(); verifyNoInteractions(ordinary);
        } finally { callers.shutdownNow(); }
    }
    @Test void stalledBodyAndHeadersShareADeadlineAndTimeoutCancelsTheExchangeWithoutRetry() throws Exception {
        var client = mock(HttpClient.class); var exchange = new CompletableFuture<HttpResponse<byte[]>>();
        when(client.<byte[]>sendAsync(any(), any())).thenReturn(exchange);
        var factory = new RecommendationHttpRequestFactory(client, Duration.ofMillis(30), mock(ClientHttpRequestFactory.class));
        try (var binding = new RecommendationCancellation().bind()) {
            assertThatThrownBy(() -> factory.createRequest(URI.create("http://ollama:11434/api/embed"), HttpMethod.POST).execute()).isInstanceOf(HttpTimeoutException.class);
        }
        assertThat(exchange.isCancelled()).isTrue(); verify(client, times(1)).sendAsync(any(), any());
    }
    @Test void standaloneCallsRetainTheirExistingTransportAndPreCancelledScopeStartsNoExchange() throws Exception {
        var client = mock(HttpClient.class); var ordinary = mock(ClientHttpRequestFactory.class);
        var factory = new RecommendationHttpRequestFactory(client, Duration.ofSeconds(1), ordinary);
        var uri = URI.create("http://ollama:11434/api/tags"); factory.createRequest(uri, HttpMethod.GET);
        verify(ordinary).createRequest(uri, HttpMethod.GET); verifyNoInteractions(client);
        var scope = new RecommendationCancellation(); scope.cancel();
        try (var binding = scope.bind()) {
            assertThatThrownBy(() -> factory.createRequest(uri, HttpMethod.GET).execute()).isInstanceOf(IOException.class);
        } finally { Thread.interrupted(); }
        verifyNoInteractions(client);
    }
    @Test void bodySubscriptionIsClosedOnCancelAndOversizeWithoutBufferingAnUnboundedResponse() {
        for (boolean cancel : new boolean[] {true, false}) {
            var body = new RecommendationHttpRequestFactory.BoundedBody(4); var subscription = mock(Flow.Subscription.class);
            body.onSubscribe(subscription);
            if (cancel) body.cancel(); else body.onNext(List.of(ByteBuffer.wrap(new byte[5])));
            verify(subscription).cancel(); assertThat(body.getBody()).isCompletedExceptionally();
            body.onComplete(); assertThat(body.getBody()).isCompletedExceptionally();
        }
    }
    @Test void errorStatusDiscardsAndClosesWithoutWaitingForOrRetainingItsSensitiveBody() {
        var body = new RecommendationHttpRequestFactory.BoundedBody(4); var subscription = mock(Flow.Subscription.class);
        body.discard(); body.onSubscribe(subscription);
        verify(subscription).cancel(); assertThat(body.getBody().join()).isEmpty();
    }
    @Test void realJdkTransportClosesOneLoopbackMockConnectionOnCancelWithoutStoppingTheMockServer() throws Exception {
        var headers = new CountDownLatch(1); var release = new CountDownLatch(1); var disconnected = new CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var serverThreads = Executors.newFixedThreadPool(2); server.setExecutor(serverThreads);
        server.createContext("/api/chat", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush(); headers.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                var block = new byte[8192];
                for (int i = 0; i < 256; i++) { exchange.getResponseBody().write(block); exchange.getResponseBody().flush(); }
            } catch (IOException expectedDisconnect) { disconnected.countDown(); }
            finally { exchange.close(); }
        });
        server.createContext("/health", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start(); var callers = Executors.newSingleThreadExecutor(); var scope = new RecommendationCancellation();
        var factory = new RecommendationHttpRequestFactory(Duration.ofSeconds(1), Duration.ofSeconds(3));
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var result = callers.submit(() -> {
                try (var binding = scope.bind()) { return catchThrowable(() -> factory.createRequest(URI.create(base + "/api/chat"), HttpMethod.POST).execute()); }
            });
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue(); scope.cancel();
            assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(IOException.class); release.countDown();
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
            try (var binding = new RecommendationCancellation().bind(); var response = factory.createRequest(URI.create(base + "/health"), HttpMethod.GET).execute()) {
                assertThat(response.getStatusCode().value()).isEqualTo(204);
            }
        } finally { release.countDown(); server.stop(0); serverThreads.shutdownNow(); callers.shutdownNow(); }
    }
    @Test void realJdkTransportPreservesHttpErrorBeforeAStalledBodyAndBoundsDelayedBodies() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var serverThreads = Executors.newFixedThreadPool(2); server.setExecutor(serverThreads); var release = new CountDownLatch(1);
        server.createContext("/error", exchange -> {
            try { exchange.sendResponseHeaders(502, 0); exchange.getResponseBody().flush(); release.await(3, TimeUnit.SECONDS); }
            catch (IOException ignored) { } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });
        server.createContext("/slow", exchange -> {
            try { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush(); release.await(3, TimeUnit.SECONDS); }
            catch (IOException ignored) { } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });
        server.start(); var base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var factory = new RecommendationHttpRequestFactory(Duration.ofSeconds(1), Duration.ofMillis(500));
            try (var binding = new RecommendationCancellation().bind(); var response = factory.createRequest(URI.create(base + "/error"), HttpMethod.GET).execute()) {
                assertThat(response.getStatusCode().value()).isEqualTo(502); assertThat(response.getBody().readAllBytes()).isEmpty();
            }
            try (var binding = new RecommendationCancellation().bind()) {
                assertThatThrownBy(() -> factory.createRequest(URI.create(base + "/slow"), HttpMethod.GET).execute()).isInstanceOf(HttpTimeoutException.class);
            }
        } finally { release.countDown(); server.stop(0); serverThreads.shutdownNow(); }
    }
}
