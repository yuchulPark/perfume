package com.perfume.ai.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PerfumeSemanticSearchServiceTests {
    private final OllamaQueryEmbeddingAdapter adapter = mock(OllamaQueryEmbeddingAdapter.class);
    private final JdbcPerfumeSemanticSearchRepository repository = mock(JdbcPerfumeSemanticSearchRepository.class);

    @Test void returnsOnlyExistingRowsAndAllowsTopKTenWhenOnlyFiveEmbeddingsExist() {
        var vector = OllamaQueryEmbeddingAdapterTests.vector(); when(adapter.embed("여름 향수")).thenReturn(vector);
        var stored = IntStream.rangeClosed(1, 5).mapToObj(i -> new PerfumeSemanticSearchResponse.Hit(i, "Stored " + i, "Brand", 1.0 / i)).toList();
        when(repository.search(vector, OllamaQueryEmbeddingAdapterTests.MODEL, 10)).thenReturn(stored);
        try (var service = service(45000)) {
            var result = service.search(new PerfumeSemanticSearchRequest("여름 향수", 10));
            assertThat(result.returnedCount()).isEqualTo(5); assertThat(result.results()).containsExactlyElementsOf(stored);
        }
        verify(adapter).embed("여름 향수"); verify(repository).search(vector, OllamaQueryEmbeddingAdapterTests.MODEL, 10);
        verifyNoMoreInteractions(adapter, repository);
    }
    @Test void noMatchesRemainAnEmptySuccessWithoutFallbackOrExtraEmbeddingRequests() {
        var vector = OllamaQueryEmbeddingAdapterTests.vector(); when(adapter.embed(anyString())).thenReturn(vector);
        when(repository.search(any(), any(), anyInt())).thenReturn(List.of());
        try (var service = service(45000)) { assertThat(service.search(new PerfumeSemanticSearchRequest("query", 5)).results()).isEmpty(); }
        verify(adapter).embed("query"); verify(repository).search(vector, OllamaQueryEmbeddingAdapterTests.MODEL, 5);
        verifyNoMoreInteractions(adapter, repository);
    }
    @Test void modelMismatchDoesNotReachTheDatabase() {
        when(adapter.embed(anyString())).thenThrow(new SemanticSearchFailure(SemanticSearchFailure.Code.MODEL_MISMATCH));
        try (var service = service(45000)) {
            assertThat(catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("query", 5))).code())
                    .isEqualTo(SemanticSearchFailure.Code.MODEL_MISMATCH);
        }
        verifyNoInteractions(repository);
    }
    @Test void internalFailuresAreSafeAndAreNeverDisguisedAsNoMatches() {
        when(adapter.embed(anyString())).thenThrow(new IllegalStateException("private-password-query-vector"));
        try (var service = service(45000)) {
            var failure = catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("query", 5)));
            assertThat(failure.code()).isEqualTo(SemanticSearchFailure.Code.SEARCH_FAILED); assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
        }
        verifyNoInteractions(repository);
    }
    @Test void timeoutKeepsTheSlotOccupiedUntilAnUninterruptibleWorkerActuallyStops() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        when(adapter.embed(anyString())).thenAnswer(call -> {
            entered.countDown(); boolean interrupted = false;
            try {
                while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException stop) { interrupted = true; } }
                if (interrupted) { Thread.currentThread().interrupt(); }
                return OllamaQueryEmbeddingAdapterTests.vector();
            } finally { stopped.countDown(); }
        });
        var callers = Executors.newSingleThreadExecutor();
        try (var service = service(200)) {
            var first = callers.submit(() -> catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("first", 5))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var busy = catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("second", 5)));
            assertThat(busy.code()).isEqualTo(SemanticSearchFailure.Code.SEARCH_BUSY);
            assertThat(first.get(2, TimeUnit.SECONDS).code()).isEqualTo(SemanticSearchFailure.Code.SEARCH_TIMEOUT);
            var stillBusy = catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("third", 5)));
            assertThat(stillBusy.code()).isEqualTo(SemanticSearchFailure.Code.SEARCH_BUSY);
            release.countDown(); assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); callers.shutdownNow(); }
        verify(adapter).embed("first"); verifyNoMoreInteractions(adapter); verifyNoInteractions(repository);
    }
    @Test void closingInterruptsTheWorkerAndRefusesFurtherWork() throws Exception {
        var entered = new CountDownLatch(1); var worker = new AtomicReference<Thread>();
        when(adapter.embed(anyString())).thenAnswer(call -> {
            worker.set(Thread.currentThread()); entered.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError(); }
            catch (InterruptedException stop) { throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_INTERRUPTED); }
        });
        var callers = Executors.newSingleThreadExecutor(); var service = service(45000);
        try {
            var first = callers.submit(() -> catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("query", 5))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); service.close();
            assertThat(first.get(2, TimeUnit.SECONDS).code()).isEqualTo(SemanticSearchFailure.Code.SEARCH_INTERRUPTED);
            worker.get().join(2000); assertThat(worker.get().isAlive()).isFalse();
            assertThat(catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("query", 5))).code()).isEqualTo(SemanticSearchFailure.Code.SEARCH_BUSY);
        } finally { service.close(); callers.shutdownNow(); }
        verifyNoInteractions(repository);
    }
    @Test void anAlreadyInterruptedCallerDoesNotStartAnyWorkAndKeepsTheFlag() {
        try (var service = service(45000)) {
            Thread.currentThread().interrupt();
            try { assertThat(catchThrowableOfType(SemanticSearchFailure.class, () -> service.search(new PerfumeSemanticSearchRequest("query", 5))).code())
                    .isEqualTo(SemanticSearchFailure.Code.SEARCH_INTERRUPTED); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
            finally { Thread.interrupted(); }
        }
        verifyNoInteractions(adapter, repository);
    }
    private PerfumeSemanticSearchService service(long deadline) { return new PerfumeSemanticSearchService(adapter, repository, options(deadline)); }
    static PerfumeSemanticSearchOptions options(long deadline) {
        return new PerfumeSemanticSearchOptions(OllamaQueryEmbeddingAdapterTests.MODEL, URI.create("http://ollama:11434"),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofMillis(deadline), 5, 1);
    }
}
