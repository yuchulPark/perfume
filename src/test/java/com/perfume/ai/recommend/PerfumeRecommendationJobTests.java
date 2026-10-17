package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.perfume.ai.search.*;
import org.junit.jupiter.api.Test;

class PerfumeRecommendationJobTests {
    static final String TOKEN = "c".repeat(64), OTHER = "d".repeat(64);
    final PerfumeSemanticSearchService search = mock(PerfumeSemanticSearchService.class);
    final JdbcRecommendationEvidenceRepository evidence = mock(JdbcRecommendationEvidenceRepository.class);
    final OllamaRecommendationClient llm = mock(OllamaRecommendationClient.class);
    final PerfumeSemanticSearchRequest request = new PerfumeSemanticSearchRequest("private-question", 5);
    void ready() {
        when(search.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of(hit(1))));
        when(evidence.find(any())).thenReturn(List.of(evidence(1)));
        when(llm.generate(any())).thenAnswer(call -> generated(call.getArgument(0)));
    }
    PerfumeRecommendationService service() { return new PerfumeRecommendationService(search, evidence, builder(), llm, options()); }
    static RecommendationJobResponse terminal(PerfumeRecommendationService service, String id, String token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) { var view = service.status(id, token); if (view.state().terminal()) return view; Thread.sleep(5); }
        throw new AssertionError("job did not settle");
    }
    static void awaitUninterruptibly(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
    static void code(Runnable action, RecommendationFailure.Code code) {
        assertThat(catchThrowableOfType(RecommendationFailure.class, action::run).code()).isEqualTo(code);
    }
    @Test void startIsFastAndCompletionReusesTheExistingResultContractAndTokenRetryDoesNotGenerateAgain() throws Exception {
        ready(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return generated(call.getArgument(0)); }).when(llm).generate(any());
        try (var service = service()) {
            var view = service.start(request, TOKEN); assertThat(view.state()).isEqualTo(RecommendationJobResponse.State.RUNNING);
            assertThat(view.result()).isNull(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.start(request, TOKEN).jobId()).isEqualTo(view.jobId());
            assertThat(service.find(TOKEN).jobId()).isEqualTo(view.jobId());
            release.countDown(); var done = terminal(service, view.jobId(), TOKEN);
            assertThat(done.state()).isEqualTo(RecommendationJobResponse.State.SUCCEEDED);
            assertThat(done.result().sources()).containsExactly(hit(1)); assertThat(done.result().answer()).contains("[1]");
            assertThat(done.toString()).doesNotContain("private-question", done.result().answer());
            assertThat(service.cancel(view.jobId(), TOKEN).state()).isEqualTo(done.state());
            verify(llm, times(1)).generate(any());
        } finally { release.countDown(); }
    }
    @Test void cancelRetainsAdmissionUntilFinallyAndDiscardsAnUninterruptibleLateResultThenAllowsANewJob() throws Exception {
        ready(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); awaitUninterruptibly(release); }
            return generated(call.getArgument(0));
        }).when(llm).generate(any());
        try (var service = service()) {
            var job = service.start(request, TOKEN); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(job.jobId(), TOKEN).state()).isEqualTo(RecommendationJobResponse.State.CANCELLING);
            assertThat(service.cancel(job.jobId(), TOKEN).state()).isEqualTo(RecommendationJobResponse.State.CANCELLING);
            code(() -> service.start(request, OTHER), RecommendationFailure.Code.RECOMMEND_BUSY);
            code(() -> service.recommend(request), RecommendationFailure.Code.RECOMMEND_BUSY);
            release.countDown(); var cancelled = terminal(service, job.jobId(), TOKEN);
            assertThat(cancelled.state()).isEqualTo(RecommendationJobResponse.State.CANCELLED); assertThat(cancelled.result()).isNull();
            var next = service.start(request, OTHER);
            assertThat(terminal(service, next.jobId(), OTHER).state()).isEqualTo(RecommendationJobResponse.State.SUCCEEDED);
        } finally { release.countDown(); }
    }
    @Test void recommendationDoesNotCompleteCancellationWhileItsActualSearchChildStillRuns() throws Exception {
        var adapter = mock(OllamaQueryEmbeddingAdapter.class); var repository = mock(JdbcPerfumeSemanticSearchRepository.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(adapter.embed(anyString())).thenAnswer(call -> { entered.countDown(); awaitUninterruptibly(release); return new float[1024]; });
        var searchOptions = new PerfumeSemanticSearchOptions(EMBEDDING, java.net.URI.create("http://ollama:11434"), java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(45), 5, 1);
        try (var actualSearch = new PerfumeSemanticSearchService(adapter, repository, searchOptions);
                var service = new PerfumeRecommendationService(actualSearch, evidence, builder(), llm, options())) {
            var job = service.start(request, TOKEN); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.cancel(job.jobId(), TOKEN);
            Thread.sleep(50); // Future cancellation has completed, but the child deliberately ignores interruption.
            assertThat(service.status(job.jobId(), TOKEN).state()).isEqualTo(RecommendationJobResponse.State.CANCELLING);
            code(() -> service.start(request, OTHER), RecommendationFailure.Code.RECOMMEND_BUSY);
            release.countDown(); assertThat(terminal(service, job.jobId(), TOKEN).state()).isEqualTo(RecommendationJobResponse.State.CANCELLED);
            verifyNoInteractions(repository, evidence, llm);
        } finally { release.countDown(); }
    }
    @Test void interruptibleWorkExitsAndSynchronousAndJobApisShareTheSameSingleAdmission() throws Exception {
        ready(); var entered = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); new CountDownLatch(1).await(); return "unreachable"; }).when(llm).generate(any());
        var callers = Executors.newSingleThreadExecutor();
        try (var service = service()) {
            var sync = callers.submit(() -> catchThrowableOfType(RecommendationFailure.class, () -> service.recommend(request)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            code(() -> service.start(request, TOKEN), RecommendationFailure.Code.RECOMMEND_BUSY);
            service.close(); assertThat(sync.get(2, TimeUnit.SECONDS).code()).isEqualTo(RecommendationFailure.Code.RECOMMEND_INTERRUPTED);
        } finally { callers.shutdownNow(); }
    }
    @Test void timeoutStaysCancellingUntilActualExitThenIsTimedOutAndDoesNotLeakASlot() throws Exception {
        ready(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); awaitUninterruptibly(release); return generated(call.getArgument(0)); }).when(llm).generate(any());
        try (var service = new PerfumeRecommendationService(search, evidence, builder(), llm, options(100, 2000))) {
            var job = service.start(request, TOKEN); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.status(job.jobId(), TOKEN).state() == RecommendationJobResponse.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(5);
            var cancelling = service.status(job.jobId(), TOKEN);
            assertThat(cancelling.state()).isEqualTo(RecommendationJobResponse.State.CANCELLING); assertThat(cancelling.errorCode()).isEqualTo("LLM_TIMEOUT");
            code(() -> service.start(request, OTHER), RecommendationFailure.Code.RECOMMEND_BUSY);
            release.countDown(); assertThat(terminal(service, job.jobId(), TOKEN).state()).isEqualTo(RecommendationJobResponse.State.TIMED_OUT);
        } finally { release.countDown(); }
    }
    @Test void invalidOwnershipAndUnknownIdsReturnTheSameSafeNotFoundAndCannotCancelWork() throws Exception {
        ready(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return generated(call.getArgument(0)); }).when(llm).generate(any());
        try (var service = service()) {
            var job = service.start(request, TOKEN); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (var id : List.of(job.jobId(), java.util.UUID.randomUUID().toString(), "bad")) {
                code(() -> service.status(id, OTHER), RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND);
                code(() -> service.cancel(id, OTHER), RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND);
            }
            code(() -> service.find("invalid"), RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND);
            assertThat(service.status(job.jobId(), TOKEN).state()).isEqualTo(RecommendationJobResponse.State.RUNNING);
            release.countDown(); terminal(service, job.jobId(), TOKEN);
        } finally { release.countDown(); }
    }
    @Test void racingCancelAndCompletionChooseOneImmutableTerminalState() throws Exception {
        ready(); var racers = Executors.newFixedThreadPool(2);
        try (var service = service()) {
            for (int i = 0; i < 12; i++) {
                var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
                doAnswer(call -> { entered.countDown(); awaitUninterruptibly(release); return generated(call.getArgument(0)); }).when(llm).generate(any());
                String token = String.format("%064x", i + 1);
                var job = service.start(request, token); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var cancel = racers.submit(() -> service.cancel(job.jobId(), token));
                var finish = racers.submit(release::countDown);
                cancel.get(2, TimeUnit.SECONDS); finish.get(2, TimeUnit.SECONDS);
                var settled = terminal(service, job.jobId(), token);
                assertThat(settled.state()).isIn(RecommendationJobResponse.State.SUCCEEDED, RecommendationJobResponse.State.CANCELLED);
                assertThat(service.cancel(job.jobId(), token).state()).isEqualTo(settled.state());
                if (settled.state() == RecommendationJobResponse.State.CANCELLED) assertThat(settled.result()).isNull();
            }
        } finally { racers.shutdownNow(); }
    }
    @Test void completedCountIsBoundedAndTtlCleanupRunsWithoutStatusRequests() throws Exception {
        ready(); var clock = new AtomicLong(System.nanoTime());
        try (var service = new PerfumeRecommendationService(search, evidence, builder(), llm, options(), new RecommendationJobOptions(1000, 2, 65536), clock::get)) {
            String first = null;
            for (int i = 1; i <= 3; i++) {
                String token = String.format("%064x", i);
                var job = service.start(request, token); terminal(service, job.jobId(), token); if (i == 1) first = job.jobId();
            }
            String evicted = first; code(() -> service.status(evicted, String.format("%064x", 1)), RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND);
            assertThat(service.retainedJobCount()).isEqualTo(2);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.retainedJobCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(service.retainedJobCount()).isZero();
        }
    }
    @Test void failureIsSanitizedAndJobRetentionSettingsHaveFiniteBounds() throws Exception {
        ready(); doThrow(new IllegalStateException("private-prompt-answer-password")).when(llm).generate(any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PerfumeRecommendationService.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try (var service = service()) {
            var job = service.start(request, TOKEN); var done = terminal(service, job.jobId(), TOKEN);
            assertThat(done.state()).isEqualTo(RecommendationJobResponse.State.FAILED);
            assertThat(done.errorCode()).isEqualTo("GENERATION_FAILED"); assertThat(done.result()).isNull();
            assertThat(done.toString()).doesNotContain("private-");
            assertThat(logs.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-", TOKEN, request.query());
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
        assertThatThrownBy(() -> new RecommendationJobOptions(0, 1, 65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationJobOptions(1000, 0, 65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationJobOptions(1000, 1, Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retainedPayloadBytesEvictOldResultsAndAnOversizeSingleResultBecomesASafeFailure() throws Exception {
        ready(); var renderer = mock(RecommendationPromptBuilder.class);
        when(renderer.build(anyString(), anyList())).thenReturn(prompt()); when(renderer.validateAndRender(anyString(), any())).thenReturn("safe answer");
        var options = new RecommendationJobOptions(600000, 64, 65536);
        try (var service = new PerfumeRecommendationService(search, evidence, renderer, llm, options(), options)) {
            doReturn(PerfumeSemanticSearchResponse.of(List.of(new PerfumeSemanticSearchResponse.Hit(1, "X".repeat(20000), "Brand", .8)))).when(search).search(any());
            var first = service.start(request, TOKEN); terminal(service, first.jobId(), TOKEN);
            var second = service.start(request, OTHER); terminal(service, second.jobId(), OTHER);
            assertThat(service.retainedJobCount()).isEqualTo(1);
            code(() -> service.status(first.jobId(), TOKEN), RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND);
            doReturn(PerfumeSemanticSearchResponse.of(List.of(new PerfumeSemanticSearchResponse.Hit(1, "X".repeat(60000), "Brand", .8)))).when(search).search(any());
            var large = service.start(request, "e".repeat(64)); var done = terminal(service, large.jobId(), "e".repeat(64));
            assertThat(done.state()).isEqualTo(RecommendationJobResponse.State.FAILED); assertThat(done.result()).isNull();
        }
    }
}
