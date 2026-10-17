package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class AutoEmbeddingContinuationTests {
    @TempDir Path directory;
    private final AutoPerfumeEmbeddingJobTests.Memory store = new AutoPerfumeEmbeddingJobTests.Memory();
    private final PerfumeEmbeddingClient client = mock(PerfumeEmbeddingClient.class);
    private final PerfumeEmbeddingText texts = new PerfumeEmbeddingText();

    AutoEmbeddingContinuationTests() { when(client.embed(any(), anyString())).thenReturn(PerfumeEmbeddingBatchTests.vector()); }

    @Test void severalSmallRangesShareOneInvocationAndACompletedCycleDoesNotRestart() throws Exception {
        add(1, 6); seed(6, range(4, 6), range(0, 2), range(2, 4));
        var report = run(1000, 100);
        assertThat(report.status()).isEqualTo("SUCCESS");
        assertThat(report.result().processed()).isEqualTo(6);
        assertThat(report.result().available()).isEqualTo(6);
        assertThat(report.result().target()).isEqualTo(6);
        assertThat(report.result().created()).isEqualTo(6);
        assertThat(report.result().lastId()).isEqualTo(4); // Queue order, not a maximum or resume cursor.
        assertThat(report.result().upperId()).isEqualTo(6);
        assertThat(report.result().hasMore()).isFalse();
        assertThat(state().pending()).isEmpty(); verify(client, times(6)).embed(any(), anyString());
        var nextCycle = run(1000, 100);
        assertThat(nextCycle.result().processed()).isEqualTo(6);
        assertThat(nextCycle.result().skipped()).isEqualTo(6);
        assertThat(nextCycle.result().created()).isZero(); verify(client, times(6)).embed(any(), anyString());
    }

    @Test void globalThousandItemBudgetCountsSkippedRowsAndAllRanges() throws Exception {
        add(1, 1100); seed(1100, range(0, 80), range(80, 300), range(300, 1100));
        for (long id = 1; id <= 80; id++) { store.hashes.put(id, texts.hash(texts.render(store.documents.get(id)))); }
        var report = run(1000, 100);
        assertThat(report.result().available()).isEqualTo(1100);
        assertThat(report.result().target()).isEqualTo(1000);
        assertThat(report.result().processed()).isEqualTo(1000);
        assertThat(report.result().created()).isEqualTo(920);
        assertThat(report.result().skipped()).isEqualTo(80);
        assertThat(report.result().lastId()).isEqualTo(1000);
        assertThat(report.result().hasMore()).isTrue();
        assertThat(state().pending()).containsExactly(range(1000, 1100));
        verify(client, times(920)).embed(any(), anyString());
        var resumed = run(1000, 100);
        assertThat(resumed.result().processed()).isEqualTo(100);
        assertThat(resumed.result().created()).isEqualTo(100);
        assertThat(resumed.result().skipped()).isZero();
    }

    @Test void aRangeLargerThanThousandResumesWithoutCheckingTheSameIdTwice() throws Exception {
        add(1, 2300); seed(2300, range(0, 2300));
        assertThat(run(1000, 100).result().processed()).isEqualTo(1000);
        assertThat(state().pending()).containsExactly(range(1000, 2300));
        assertThat(run(1000, 100).result().processed()).isEqualTo(1000);
        assertThat(state().pending()).containsExactly(range(2000, 2300));
        var last = run(1000, 100);
        assertThat(last.result().processed()).isEqualTo(300); assertThat(last.result().skipped()).isZero();
        assertThat(last.result().hasMore()).isFalse(); assertThat(store.hashes).hasSize(2300);
        verify(client, times(2300)).embed(any(), anyString());
    }

    @Test void failedPrefixIsDeferredWhileAnUninspectedSuffixAndOtherRangesContinue() throws Exception {
        add(1, 8); seed(8, range(0, 6), range(6, 8));
        when(client.embed(any(), contains("Perfume 1\n"))).thenThrow(new EmbeddingFailure(EmbeddingFailure.Code.TIMEOUT));
        var failed = run(1000, 2);
        assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.code()).isEqualTo("PARTIAL_FAILURE");
        assertThat(failed.result().processed()).isEqualTo(8); assertThat(failed.result().failed()).isEqualTo(1);
        assertThat(failed.result().created()).isEqualTo(7); assertThat(failed.result().hasMore()).isTrue();
        assertThat(state().pending()).containsExactly(range(0, 2));
        verify(client).embed(PerfumeEmbeddingBatchTests.model(), texts.render(store.documents.get(1L)));
        var again = run(1000, 2);
        assertThat(again.result().processed()).isEqualTo(2); assertThat(again.result().failed()).isEqualTo(1);
        assertThat(again.result().skipped()).isEqualTo(1);
        verify(client, times(2)).embed(PerfumeEmbeddingBatchTests.model(), texts.render(store.documents.get(1L)));
        store.add(1, "Repaired");
        var repaired = run(1000, 2);
        assertThat(repaired.result().created()).isEqualTo(1); assertThat(repaired.result().skipped()).isEqualTo(1);
        assertThat(state().pending()).isEmpty();
    }

    @Test void onlyAFailedItemCannotBeRetriedToArtificiallyFillTheBudget() throws Exception {
        add(1, 1); when(client.embed(any(), anyString())).thenThrow(new EmbeddingFailure(EmbeddingFailure.Code.HTTP_ERROR));
        var report = run(1000, 25);
        assertThat(report.result().processed()).isEqualTo(1); assertThat(report.result().failed()).isEqualTo(1);
        assertThat(report.result().hasMore()).isTrue(); assertThat(state().pending()).containsExactly(range(0, 1));
        verify(client).embed(any(), anyString());
    }

    @Test void deadlineIsSharedAcrossRangesAndDoesNotResetAtEachCall() throws Exception {
        for (long id : new long[]{1, 10, 20, 30}) { store.add(id, "Perfume " + id); }
        seed(30, range(0, 1), range(1, 10), range(10, 20), range(20, 30));
        var clock = new AtomicLong();
        when(client.embed(any(), anyString())).thenAnswer(call -> { clock.addAndGet(400_000_000); return PerfumeEmbeddingBatchTests.vector(); });
        var report = run(store, 1000, 25, Duration.ofSeconds(1), () -> false, clock::get);
        assertThat(report.status()).isEqualTo("INTERRUPTED"); assertThat(report.code()).isEqualTo("TIME_LIMIT");
        assertThat(report.elapsedMs()).isEqualTo(1200); assertThat(report.result().processed()).isEqualTo(3);
        assertThat(report.result().interrupted()).isTrue();
        assertThat(state().pending()).containsExactly(range(20, 30), range(10, 20));
        assertThat(store.hashes).containsOnlyKeys(1L, 10L, 20L); verify(client, times(3)).embed(any(), anyString());
    }

    @Test void cancellationAfterACompletedRangeStartsNoOtherWorkAndResumeKeepsCommits() throws Exception {
        add(1, 4); seed(4, range(0, 1), range(1, 4));
        var stopping = new AtomicBoolean();
        when(client.embed(any(), anyString())).thenAnswer(call -> { stopping.set(true); return PerfumeEmbeddingBatchTests.vector(); });
        var report = run(store, 1000, 25, Duration.ofHours(1), stopping::get, System::nanoTime);
        assertThat(report.code()).isEqualTo("STOPPED"); assertThat(report.result().processed()).isEqualTo(1);
        assertThat(state().pending()).containsExactly(range(1, 4), range(0, 1));
        when(client.embed(any(), anyString())).thenReturn(PerfumeEmbeddingBatchTests.vector());
        var resumed = run(1000, 25);
        assertThat(resumed.result().processed()).isEqualTo(4); assertThat(resumed.result().created()).isEqualTo(3);
        assertThat(resumed.result().skipped()).isEqualTo(1); assertThat(state().pending()).isEmpty();
        verify(client, times(4)).embed(any(), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stoppingBeforeAdmissionDoesNotOpenDatabaseOrLoseExistingWork(boolean interrupt) throws Exception {
        add(1, 4); seed(4, range(0, 4));
        var never = mock(PerfumeEmbeddingStore.class);
        try {
            if (interrupt) { Thread.currentThread().interrupt(); }
            var report = run(never, 1000, 25, Duration.ofHours(1), () -> !interrupt, System::nanoTime);
            assertThat(report.code()).isEqualTo("STOPPED"); assertThat(report.result().processed()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupt); verifyNoInteractions(never, client);
        } finally { Thread.interrupted(); }
        assertThat(state().pending()).containsExactly(range(0, 4));
    }

    @Test void fatalDatabaseFailureStopsLaterRangesAndPreservesEarlierSuccessfulCommits() throws Exception {
        add(1, 6); seed(6, range(0, 2), range(2, 4), range(4, 6)); store.failSave = 3;
        var report = run(1000, 2);
        assertThat(report.status()).isEqualTo("FAILED"); assertThat(report.code()).isEqualTo("DATABASE");
        assertThat(report.result().processed()).isEqualTo(3); assertThat(report.result().created()).isEqualTo(2);
        assertThat(report.result().failed()).isEqualTo(1); assertThat(store.hashes).containsOnlyKeys(1L, 2L);
        assertThat(state().pending()).containsExactly(range(4, 6), range(3, 4), range(2, 3));
        verify(client, times(3)).embed(any(), anyString());
        store.failSave = 0;
        assertThat(run(1000, 2).result().created()).isEqualTo(4); assertThat(store.hashes).hasSize(6);
    }

    @Test void advisoryLockCollisionBetweenChunksAbortsWithoutClaimingWholeRunWasSkipped() throws Exception {
        add(1, 6); seed(6, range(0, 6)); var opens = new AtomicInteger();
        PerfumeEmbeddingStore guarded = () -> {
            if (opens.incrementAndGet() == 3) { throw new EmbeddingFailure(EmbeddingFailure.Code.BATCH_BUSY); }
            return store.open();
        };
        var report = run(guarded, 1000, 2, Duration.ofHours(1), () -> false, System::nanoTime);
        assertThat(report.status()).isEqualTo("FAILED"); assertThat(report.code()).isEqualTo("BATCH_BUSY");
        assertThat(report.result().processed()).isEqualTo(2); assertThat(report.result().hasMore()).isTrue();
        assertThat(state().pending()).containsExactly(range(2, 6)); assertThat(store.hashes).containsOnlyKeys(1L, 2L);
        verify(client, times(2)).embed(any(), anyString());
    }

    @Test void aSecondWorkerCannotReadOrReplaceTheLockedCheckpoint() throws Exception {
        seed(6, range(0, 6)); var original = Files.readAllBytes(directory.resolve("state.json"));
        var source = mock(PerfumeEmbeddingStore.class);
        try (var held = new AutoEmbeddingState(directory)) {
            assertThatThrownBy(() -> run(source, 1000, 25, Duration.ofHours(1), () -> false, System::nanoTime))
                    .hasMessage("Automatic embedding stopped: STATE_OR_EXECUTION_ERROR").hasNoCause();
        }
        assertThat(Files.readAllBytes(directory.resolve("state.json"))).isEqualTo(original);
        verifyNoInteractions(source, client);
    }

    @Test void threadInterruptionDuringWorkStillWritesARecoverableCheckpointAndRestoresTheFlag() throws Exception {
        add(1, 3); seed(3, range(0, 3));
        when(client.embed(any(), anyString())).thenAnswer(call -> { Thread.currentThread().interrupt(); return PerfumeEmbeddingBatchTests.vector(); });
        try {
            var report = run(1000, 2);
            assertThat(report.code()).isEqualTo("STOPPED"); assertThat(report.result().processed()).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(state().pending()).containsExactly(range(1, 3), range(0, 1));
        assertThat(store.hashes).containsOnlyKeys(1L); verify(client).embed(any(), anyString());
    }

    @Test void eachChunkIsCheckpointedAndACrashAfterLaterUpsertReplaysOnlyUnacknowledgedWork() throws Exception {
        add(1, 5); seed(5, range(0, 5));
        PerfumeEmbeddingStore crashing = () -> new DelegatingSession(store.open()) {
            @Override public boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] vector) {
                boolean saved = super.save(id, model, text, hash, vector);
                if (id == 3) {
                    var snapshot = rawState();
                    assertThat(snapshot.latest().status()).isEqualTo("RUNNING");
                    assertThat(snapshot.latest().result().processed()).isEqualTo(2);
                    assertThat(snapshot.pending()).containsExactly(range(2, 5));
                    throw new AssertionError("simulated process death after committed upsert");
                }
                return saved;
            }
        };
        assertThatThrownBy(() -> run(crashing, 1000, 2, Duration.ofHours(1), () -> false, System::nanoTime)).isInstanceOf(AssertionError.class);
        assertThat(state().pending()).containsExactly(range(2, 5)); assertThat(store.hashes).containsOnlyKeys(1L, 2L, 3L);
        var resumed = run(1000, 2);
        assertThat(resumed.result().processed()).isEqualTo(3); assertThat(resumed.result().created()).isEqualTo(2);
        assertThat(resumed.result().skipped()).isEqualTo(1); verify(client, times(5)).embed(any(), anyString());
    }

    @Test void higherIdsPresentAtAdmissionAreAddedButIdsArrivingDuringRunWaitUntilNextInvocation() throws Exception {
        add(1, 4); seed(2, range(0, 2));
        when(client.embed(any(), anyString())).thenAnswer(call -> { store.add(10, "New later"); return PerfumeEmbeddingBatchTests.vector(); });
        var report = run(1000, 2);
        assertThat(report.result().processed()).isEqualTo(4); assertThat(report.result().upperId()).isEqualTo(4);
        assertThat(state().observedUpperId()).isEqualTo(4); assertThat(state().pending()).isEmpty();
        var next = run(1000, 2);
        assertThat(next.result().created()).isEqualTo(1); assertThat(next.result().skipped()).isEqualTo(4);
        assertThat(next.result().upperId()).isEqualTo(10);
    }

    @Test void legacyVersionOneJsonWithUnsortedAdjacentRangesIsReadWithoutMigration() throws Exception {
        for (long id : new long[]{125, 129, 130, 500, 2332}) { store.add(id, "Perfume " + id); }
        Files.writeString(directory.resolve("state.json"), """
                {"version":1,"model":{"name":"qwen3-embedding:0.6b","digest":"%s","textVersion":"perfume-v1"},
                "observedUpperId":2332,"pending":[{"afterId":129,"upperId":2332},{"afterId":124,"upperId":129}],
                "lastSuccessAt":"2026-10-18T00:00:00Z","latest":{"startedAt":"2026-10-18T00:00:00Z",
                "finishedAt":"2026-10-18T00:00:01Z","status":"SUCCESS","code":"OK","elapsedMs":1000,
                "total":2294,"result":null,"nextCursor":129,"pendingRanges":2}}
                """.formatted(PerfumeEmbeddingBatchTests.model().digest()));
        var report = run(1000, 2);
        assertThat(report.result().processed()).isEqualTo(5); assertThat(report.result().created()).isEqualTo(5);
        assertThat(state().version()).isEqualTo(1); assertThat(state().pending()).isEmpty();
    }

    @Test void accumulatedLogIncludesEveryRangeAndReportsHasMoreForDeferredFailures() throws Exception {
        add(1, 4); seed(4, range(0, 2), range(2, 4));
        when(client.embed(any(), contains("Perfume 1\n"))).thenThrow(new EmbeddingFailure(EmbeddingFailure.Code.INVALID_RESPONSE));
        var logger = (Logger) LoggerFactory.getLogger(AutoPerfumeEmbeddingJob.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var report = run(1000, 2);
            var text = logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(line -> line.startsWith("Automatic embedding finished:")).toList();
            assertThat(text).hasSize(1);
            assertThat(text.get(0)).contains("status=FAILED", "code=PARTIAL_FAILURE", "total=4", "available=4", "target=4", "processed=4",
                    "new=3", "changed=0", "skipped=0", "failed=1", "lastId=4", "upperId=4", "hasMore=true", "interrupted=false", "nextCursor=0", "pendingRanges=1")
                    .doesNotContain("Perfume 1", "[1.0", "password");
            assertThat(report.result().hasMore()).isTrue(); assertThat(state().latest()).isEqualTo(report);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void overlappingAndDuplicateRangesRemainRejectedWithoutChangingTheExistingFile() throws Exception {
        seed(10, range(0, 10)); var original = Files.readAllBytes(directory.resolve("state.json"));
        assertThatThrownBy(() -> seed(10, range(0, 5), range(4, 10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> seed(10, range(0, 5), range(0, 5))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(directory.resolve("state.json"))).isEqualTo(original);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unexpectedBatchProgressCannotDropWorkOrCauseAnUnboundedLoop(boolean invalidCursor) throws Exception {
        add(1, 4); seed(4, range(0, 4));
        var batch = mock(PerfumeEmbeddingBatch.class);
        when(batch.execute(any(), eq(4L), any())).thenReturn(new PerfumeEmbeddingBatch.Result(4, 2,
                invalidCursor ? 1 : 0, 0, 0, 0, 0, invalidCursor ? 5 : 0, 4, true, false));
        var report = new AutoPerfumeEmbeddingJob(store, batch).run(PerfumeEmbeddingBatchTests.options(10, 2, 0,
                PerfumeEmbeddingBatchTests.model()), new AutoEmbeddingOptions(directory, Duration.ofHours(1)), () -> false);
        assertThat(report.code()).isEqualTo(invalidCursor ? "INVALID_PROGRESS" : "NO_PROGRESS");
        assertThat(report.status()).isEqualTo("FAILED"); assertThat(report.result().processed()).isZero();
        assertThat(state().pending()).containsExactly(range(0, 4));
        verify(batch).execute(any(), eq(4L), any()); verifyNoMoreInteractions(batch); verifyNoInteractions(client);
    }

    private void add(int from, int to) { for (long id = from; id <= to; id++) { store.add(id, "Perfume " + id); } }
    private AutoEmbeddingState.Report run(int limit, int page) { return run(store, limit, page, Duration.ofHours(1), () -> false, System::nanoTime); }
    private AutoEmbeddingState.Report run(PerfumeEmbeddingStore source, int limit, int page, Duration runtime, BooleanSupplier stopping, LongSupplier clock) {
        return new AutoPerfumeEmbeddingJob(source, new PerfumeEmbeddingBatch(source, client, texts), clock).run(
                PerfumeEmbeddingBatchTests.options(limit, page, 0, PerfumeEmbeddingBatchTests.model()), new AutoEmbeddingOptions(directory, runtime), stopping);
    }
    private void seed(long upper, AutoEmbeddingState.Range... ranges) throws Exception {
        try (var file = new AutoEmbeddingState(directory)) {
            file.write(new AutoEmbeddingState.Snapshot(1, PerfumeEmbeddingBatchTests.model(), upper, List.of(ranges), null, null));
        }
    }
    private static AutoEmbeddingState.Range range(long after, long upper) { return new AutoEmbeddingState.Range(after, upper); }
    private AutoEmbeddingState.Snapshot state() throws Exception { try (var file = new AutoEmbeddingState(directory)) { return file.read(PerfumeEmbeddingBatchTests.model()); } }
    private AutoEmbeddingState.Snapshot rawState() {
        try { return new ObjectMapper().readValue(directory.resolve("state.json").toFile(), AutoEmbeddingState.Snapshot.class); }
        catch (java.io.IOException failure) { throw new AssertionError("Cannot read temporary checkpoint", failure); }
    }
    private static class DelegatingSession implements PerfumeEmbeddingStore.Session {
        private final PerfumeEmbeddingStore.Session delegate;
        DelegatingSession(PerfumeEmbeddingStore.Session delegate) { this.delegate = delegate; }
        public PerfumeEmbeddingStore.Window window(long after) { return delegate.window(after); }
        public PerfumeEmbeddingStore.Window window(long after, long upper) { return delegate.window(after, upper); }
        public List<Long> nextIds(long after, long upper, int limit) { return delegate.nextIds(after, upper, limit); }
        public PerfumeEmbeddingDocument load(long id) { return delegate.load(id); }
        public Optional<String> findHash(long id, EmbeddingModelIdentity model) { return delegate.findHash(id, model); }
        public boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] vector) { return delegate.save(id, model, text, hash, vector); }
        public void close() { delegate.close(); }
    }
}
