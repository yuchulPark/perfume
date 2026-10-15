package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import com.perfume.ai.search.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PerfumeRecommendationServiceTests {
    private final PerfumeSemanticSearchService search = mock(PerfumeSemanticSearchService.class);
    private final JdbcRecommendationEvidenceRepository evidence = mock(JdbcRecommendationEvidenceRepository.class);
    private final OllamaRecommendationClient llm = mock(OllamaRecommendationClient.class);

    @ParameterizedTest @ValueSource(strings = {"여름에 사용하기 좋은 향수", "fresh fragrance for summer"})
    void reusesSearchOnceAndReturnsOnlyItsFiveSourcesEvenForTopKTen(String query) {
        var hits = IntStream.rangeClosed(1, 5).mapToObj(RecommendationTestSupport::hit).toList();
        var snapshots = IntStream.rangeClosed(1, 5).mapToObj(RecommendationTestSupport::evidence).toList();
        when(search.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(hits)); when(evidence.find(hits)).thenReturn(snapshots);
        when(llm.generate(any())).thenAnswer(call -> generated(call.getArgument(0)));
        try (var service = service(options())) {
            var result = service.recommend(new PerfumeSemanticSearchRequest(query, 10));
            assertThat(result.sources()).containsExactlyElementsOf(hits); assertThat(result.returnedCount()).isEqualTo(5);
            assertThat(result.answer()).contains("[1]", "저장 정보").doesNotContain("Fake", "perfumeId=999");
            assertThat(result.toString()).doesNotContain("[1]", "저장 정보", query);
        }
        var order = inOrder(search, evidence, llm); order.verify(search).search(new PerfumeSemanticSearchRequest(query, 10));
        order.verify(evidence).find(hits); order.verify(llm).generate(any()); verifyNoMoreInteractions(search, evidence, llm);
    }
    @Test void noSearchHitsMeansNoEvidenceReadAndNoLlmOrFallback() {
        when(search.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of()));
        try (var service = service(options())) {
            var result = service.recommend(new PerfumeSemanticSearchRequest("향수", null));
            assertThat(result.sources()).isEmpty(); assertThat(result.returnedCount()).isZero(); assertThat(result.answer()).contains("근거가 없어");
        }
        verifyNoInteractions(evidence, llm);
    }
    @ParameterizedTest @CsvSource({"DATABASE_UNAVAILABLE,DATABASE_UNAVAILABLE", "DATABASE_TIMEOUT,DATABASE_TIMEOUT", "MODEL_MISMATCH,MODEL_MISMATCH",
            "OLLAMA_TIMEOUT,SEARCH_TIMEOUT", "SEARCH_TIMEOUT,SEARCH_TIMEOUT", "SEARCH_BUSY,RECOMMEND_BUSY", "OLLAMA_INVALID_RESPONSE,SEARCH_FAILED"})
    void searchFailuresStopBeforeEvidenceAndGeneration(SemanticSearchFailure.Code input, RecommendationFailure.Code expected) {
        when(search.search(any())).thenThrow(new SemanticSearchFailure(input));
        try (var service = service(options())) { assertFailure(service, expected); }
        verifyNoInteractions(evidence, llm);
    }
    @ParameterizedTest @ValueSource(strings = {"EVIDENCE_UNAVAILABLE", "DATABASE_TIMEOUT", "DATABASE_UNAVAILABLE"})
    void evidenceFailuresStopBeforeCallingTheLlm(String input) {
        var code = RecommendationFailure.Code.valueOf(input);
        when(search.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of(hit(1)))); when(evidence.find(anyList())).thenThrow(new RecommendationFailure(code));
        try (var service = service(options())) { assertFailure(service, code); }
        verifyNoInteractions(llm);
    }
    @Test void actualReadOnlyTransactionIsClosedBeforeWaitingForLlm() throws Exception {
        var fixture = new JdbcRecommendationEvidenceRepositoryTests.Fixture(1);
        when(search.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of(hit(1))));
        when(llm.generate(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(fixture.lifecycle).containsExactly("query", "commit", "close"); return generated(call.getArgument(0));
        });
        try (var service = new PerfumeRecommendationService(search, fixture.repository, builder(), llm, options())) {
            assertThat(service.recommend(new PerfumeSemanticSearchRequest("향수", 5)).returnedCount()).isEqualTo(1);
        }
        verify(fixture.statement, never()).executeUpdate();
    }
    @Test void actualSingleWorkerSearchAndRecommendationExecutorsDoNotDeadlockOrDuplicateEmbeddingCalls() throws Exception {
        var adapter = mock(OllamaQueryEmbeddingAdapter.class); var vectors = mock(JdbcPerfumeSemanticSearchRepository.class);
        var vector = new float[1024]; vector[0] = 1;
        when(adapter.embed("여름 향수")).thenReturn(vector); when(vectors.search(vector, EMBEDDING, 5)).thenReturn(List.of(hit(1)));
        var fixture = new JdbcRecommendationEvidenceRepositoryTests.Fixture(1);
        var searchOptions = new PerfumeSemanticSearchOptions(EMBEDDING, java.net.URI.create("http://ollama:11434"),
                java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(45), 5, 1);
        when(llm.generate(any())).thenAnswer(call -> generated(call.getArgument(0)));
        try (var actualSearch = new PerfumeSemanticSearchService(adapter, vectors, searchOptions);
                var service = new PerfumeRecommendationService(actualSearch, fixture.repository, builder(), llm, options())) {
            assertThat(service.recommend(new PerfumeSemanticSearchRequest("여름 향수", 5)).returnedCount()).isEqualTo(1);
        }
        verify(adapter).embed("여름 향수"); verify(vectors).search(vector, EMBEDDING, 5); verifyNoMoreInteractions(adapter, vectors);
        verify(fixture.statement, never()).executeUpdate();
    }
    @ParameterizedTest @ValueSource(strings = {"{\"answer\":\"Stored 1은 24시간 지속됩니다. [1]\"}",
            "{\"picks\":[{\"source\":1,\"factId\":99}]}", "{\"picks\":[{\"source\":2,\"factId\":1}]}",
            "{\"picks\":[{\"source\":1,\"factId\":1}],\"answer\":\"Stored 1은 24시간 지속됩니다. [1]\"}"})
    void unsupportedClaimsOrSelectionsFailWithoutRegeneration(String generated) {
        ready(); when(llm.generate(any())).thenReturn(generated);
        try (var service = service(options())) { assertFailure(service, RecommendationFailure.Code.GENERATION_FAILED); }
        verify(llm).generate(any()); verifyNoMoreInteractions(llm);
    }
    @Test void unknownInternalFailuresAreSanitizedRatherThanEmptySuccesses() {
        ready(); when(llm.generate(any())).thenThrow(new IllegalStateException("private-prompt-password-answer"));
        try (var service = service(options())) {
            var failure = catchThrowableOfType(RecommendationFailure.class, () -> service.recommend(new PerfumeSemanticSearchRequest("private-query", 5)));
            assertThat(failure.code()).isEqualTo(RecommendationFailure.Code.GENERATION_FAILED); assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
        }
    }
    @Test void generationTimeoutAndBusyLimitRetainTheSlotUntilUninterruptibleWorkActuallyExits() throws Exception {
        ready(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
        when(llm.generate(any())).thenAnswer(call -> {
            entered.countDown(); boolean interrupted = false;
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
            if (interrupted) { Thread.currentThread().interrupt(); }
            exited.countDown(); return generated(call.getArgument(0));
        });
        var caller = Executors.newSingleThreadExecutor();
        try (var service = service(options(200, 3000))) {
            var first = caller.submit(() -> catchThrowableOfType(RecommendationFailure.class, () -> service.recommend(new PerfumeSemanticSearchRequest("향수", 5))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); assertFailure(service, RecommendationFailure.Code.RECOMMEND_BUSY);
            assertThat(first.get(2, TimeUnit.SECONDS).code()).isEqualTo(RecommendationFailure.Code.LLM_TIMEOUT);
            assertFailure(service, RecommendationFailure.Code.RECOMMEND_BUSY);
            verify(search, times(1)).search(any()); verify(evidence, times(1)).find(anyList());
            release.countDown(); assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); caller.shutdownNow(); assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void overallDeadlineCanExpireDuringSearchAndNeverStartsLlm() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(search.search(any())).thenAnswer(call -> {
            entered.countDown(); boolean interrupted = false;
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
            if (interrupted) { Thread.currentThread().interrupt(); } return PerfumeSemanticSearchResponse.of(List.of(hit(1)));
        });
        var caller = Executors.newSingleThreadExecutor();
        try (var service = service(options(2000, 200))) {
            var first = caller.submit(() -> catchThrowableOfType(RecommendationFailure.class, () -> service.recommend(new PerfumeSemanticSearchRequest("향수", 5))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(first.get(2, TimeUnit.SECONDS).code()).isEqualTo(RecommendationFailure.Code.RECOMMEND_TIMEOUT);
            assertFailure(service, RecommendationFailure.Code.RECOMMEND_BUSY); verifyNoInteractions(evidence, llm);
        } finally { release.countDown(); caller.shutdownNow(); caller.awaitTermination(2, TimeUnit.SECONDS); }
    }
    @Test void shutdownInterruptsActiveWorkerAndDoesNotAcceptMoreWork() throws Exception {
        ready(); var entered = new CountDownLatch(1); var worker = new AtomicReference<Thread>();
        when(llm.generate(any())).thenAnswer(call -> {
            worker.set(Thread.currentThread()); entered.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            OllamaRecommendationClient.checkInterrupted(); return "unreachable";
        });
        var caller = Executors.newSingleThreadExecutor(); var service = service(options());
        try {
            var first = caller.submit(() -> catchThrowableOfType(RecommendationFailure.class, () -> service.recommend(new PerfumeSemanticSearchRequest("향수", 5))));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); service.close();
            assertThat(first.get(2, TimeUnit.SECONDS).code()).isEqualTo(RecommendationFailure.Code.RECOMMEND_INTERRUPTED);
            worker.get().join(2000); assertThat(worker.get().isAlive()).isFalse(); assertFailure(service, RecommendationFailure.Code.RECOMMEND_BUSY);
        } finally { service.close(); caller.shutdownNow(); }
    }
    @Test void preInterruptedCallerStartsNoWorkAndKeepsItsInterruptFlag() {
        try (var service = service(options())) {
            Thread.currentThread().interrupt();
            try { assertFailure(service, RecommendationFailure.Code.RECOMMEND_INTERRUPTED); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
            finally { Thread.interrupted(); }
        }
        verifyNoInteractions(search, evidence, llm);
    }
    @Test void interruptedWaitingCallerCancelsWorkerAndPreservesItsInterruptFlag() throws Exception {
        ready(); var entered = new CountDownLatch(1); var finished = new CountDownLatch(1); var keptInterrupt = new AtomicBoolean();
        when(llm.generate(any())).thenAnswer(call -> { entered.countDown(); new CountDownLatch(1).await(); return "unreachable"; });
        try (var service = service(options())) {
            var caller = new Thread(() -> {
                try { assertFailure(service, RecommendationFailure.Code.RECOMMEND_INTERRUPTED); keptInterrupt.set(Thread.currentThread().isInterrupted()); }
                finally { finished.countDown(); }
            });
            caller.start(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); caller.interrupt();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue(); caller.join(2000); assertThat(keptInterrupt).isTrue();
        }
    }
    private void ready() {
        when(search.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of(hit(1)))); when(evidence.find(anyList())).thenReturn(List.of(evidence(1)));
    }
    private PerfumeRecommendationService service(PerfumeRecommendationOptions options) { return new PerfumeRecommendationService(search, evidence, builder(), llm, options); }
    private static void assertFailure(PerfumeRecommendationService service, RecommendationFailure.Code code) {
        assertThat(catchThrowableOfType(RecommendationFailure.class, () -> service.recommend(new PerfumeSemanticSearchRequest("향수", 5))).code()).isEqualTo(code);
    }
}
