package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class AutoPerfumeEmbeddingJobTests {
    @TempDir Path directory;
    final Memory store = new Memory();
    final PerfumeEmbeddingClient client = mock(PerfumeEmbeddingClient.class);
    final PerfumeEmbeddingBatch batch = new PerfumeEmbeddingBatch(store, client, new PerfumeEmbeddingText());

    AutoPerfumeEmbeddingJobTests() {
        when(client.embed(any(), anyString())).thenReturn(PerfumeEmbeddingBatchTests.vector());
    }

    AutoEmbeddingState.Report run(int limit) { return run(limit, () -> false); }
    AutoEmbeddingState.Report run(int limit, java.util.function.BooleanSupplier stop) {
        // A new job and state-file instance each time exercises restart recovery, not in-memory cursor reuse.
        return new AutoPerfumeEmbeddingJob(store, batch).run(PerfumeEmbeddingBatchTests.options(limit, 2, 0,
                PerfumeEmbeddingBatchTests.model()), new AutoEmbeddingOptions(directory, Duration.ofHours(1)), stop);
    }
    AutoEmbeddingState.Snapshot state() throws Exception {
        try (var file = new AutoEmbeddingState(directory)) { return file.read(PerfumeEmbeddingBatchTests.model()); }
    }

    @Test void firstRunGapsBoundedInspectionExistingHashesChangedDataAndFreshCycle() throws Exception {
        store.add(2, "A"); store.add(80, "B"); store.add(900, "C");
        var first = run(2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.result().created()).isEqualTo(2);
        assertThat(first.result().processed()).isEqualTo(2);
        assertThat(first.nextCursor()).isEqualTo(80);
        assertThat(run(2).result().created()).isEqualTo(1);
        assertThat(state().pending()).isEmpty();
        store.add(2, "Changed");
        var cycle = run(2);
        assertThat(cycle.result().changed()).isEqualTo(1);
        assertThat(cycle.result().skipped()).isEqualTo(1);
        assertThat(run(2).result().skipped()).isEqualTo(1);
        verify(client, times(4)).embed(any(), anyString());
        assertThat(store.hashes).hasSize(3);
    }

    @Test void failedPrefixIsRetainedButLaterAndNewIdsAreNotStarved() throws Exception {
        store.add(1, "Always fails"); store.add(8, "B"); store.add(90, "C");
        when(client.embed(any(), contains("Always fails"))).thenThrow(new EmbeddingFailure(EmbeddingFailure.Code.HTTP_ERROR));
        var failed = run(2);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.result().created()).isEqualTo(1);
        assertThat(state().pending()).containsExactly(new AutoEmbeddingState.Range(8, 90), new AutoEmbeddingState.Range(0, 8));
        store.add(1000, "New");
        assertThat(run(2).result().lastId()).isEqualTo(90);
        assertThat(run(2).status()).isEqualTo("FAILED");
        assertThat(run(2).result().lastId()).isEqualTo(1000);
        assertThat(store.hashes).containsKeys(8L, 90L, 1000L).doesNotContainKey(1L);
        // Repairing a failure resumes the retained range; already committed B is skipped.
        store.add(1, "Repaired");
        var repaired = run(2);
        assertThat(repaired.result().created()).isEqualTo(1);
        assertThat(repaired.result().skipped()).isEqualTo(1);
        assertThat(state().pending()).isEmpty();
    }

    @Test void newHigherIdAddedDuringInferenceIsAdmittedOnFollowingInvocation() {
        store.add(1, "A"); store.add(9, "B");
        when(client.embed(any(), anyString())).thenAnswer(call -> {
            store.add(100, "Arrived during inference"); return PerfumeEmbeddingBatchTests.vector();
        });
        assertThat(run(1).result().upperId()).isEqualTo(9);
        assertThat(run(1).result().lastId()).isEqualTo(9);
        assertThat(run(1).result().lastId()).isEqualTo(100);
    }

    @Test void cooperativeStopPreservesCommitAndReplaysInspectedPrefixAfterRestart() throws Exception {
        store.add(1, "A"); store.add(2, "B");
        var stop = new AtomicBoolean();
        when(client.embed(any(), anyString())).thenAnswer(call -> { stop.set(true); return PerfumeEmbeddingBatchTests.vector(); });
        var interrupted = run(2, stop::get);
        assertThat(interrupted.status()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.code()).isEqualTo("STOPPED");
        assertThat(store.hashes).containsOnlyKeys(1L);
        assertThat(state().pending()).containsExactly(new AutoEmbeddingState.Range(1, 2), new AutoEmbeddingState.Range(0, 1));
        when(client.embed(any(), anyString())).thenReturn(PerfumeEmbeddingBatchTests.vector());
        assertThat(run(2).result().created()).isEqualTo(1);
        assertThat(run(2).result().skipped()).isEqualTo(1);
        verify(client, times(2)).embed(any(), anyString());
    }

    @Test void runtimeDeadlineStopsBetweenItemsAndRecordsInterruption() {
        store.add(1, "A"); store.add(2, "B");
        when(client.embed(any(), anyString())).thenAnswer(call -> { Thread.sleep(1100); return PerfumeEmbeddingBatchTests.vector(); });
        var result = new AutoPerfumeEmbeddingJob(store, batch).run(PerfumeEmbeddingBatchTests.options(2, 2, 0,
                PerfumeEmbeddingBatchTests.model()), new AutoEmbeddingOptions(directory, Duration.ofSeconds(1)), () -> false);
        assertThat(result.status()).isEqualTo("INTERRUPTED");
        assertThat(result.code()).isEqualTo("TIME_LIMIT");
        assertThat(result.result().processed()).isEqualTo(1);
        assertThat(store.hashes).containsOnlyKeys(1L);
    }

    @Test void databaseFailureAfterOneCommitRetainsPrefixAndResumesWithoutLosingEarlierEmbedding() throws Exception {
        store.add(1, "A"); store.add(5, "B"); store.add(70, "C");
        store.failSave = 5;
        var failed = run(3);
        assertThat(failed.code()).isEqualTo("DATABASE");
        assertThat(failed.result().created()).isEqualTo(1);
        assertThat(store.hashes).containsOnlyKeys(1L);
        assertThat(state().pending()).containsExactly(new AutoEmbeddingState.Range(5, 70), new AutoEmbeddingState.Range(0, 5));
        store.failSave = 0;
        assertThat(run(3).result().created()).isEqualTo(1);
        var retry = run(3);
        assertThat(retry.result().skipped()).isEqualTo(1);
        assertThat(retry.result().created()).isEqualTo(1);
        assertThat(store.hashes).hasSize(3);
    }

    @Test void crashBetweenUpsertAndCheckpointReplaysSavedRangeWithoutDeletingEmbeddings() throws Exception {
        store.add(10, "A"); store.add(50, "B");
        try (var file = new AutoEmbeddingState(directory)) {
            file.write(new AutoEmbeddingState.Snapshot(1, PerfumeEmbeddingBatchTests.model(), 50,
                    List.of(new AutoEmbeddingState.Range(0, 50)), null, null));
        }
        // Simulates durable pre-run checkpoint plus a committed upsert; no VM power-off is claimed.
        var texts = new PerfumeEmbeddingText();
        store.hashes.put(10L, texts.hash(texts.render(store.documents.get(10L))));
        var resumed = run(10);
        assertThat(resumed.result().created()).isEqualTo(1);
        assertThat(resumed.result().skipped()).isEqualTo(1);
        assertThat(state().pending()).isEmpty();
        assertThat(store.hashes).hasSize(2);
        verify(client, times(1)).embed(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = EmbeddingFailure.Code.class, names = {"MODEL_MISMATCH", "TIMEOUT", "HTTP_ERROR", "CONNECTION_ERROR"})
    void inferenceFailureNeverDropsPendingRangeOrOldEmbedding(EmbeddingFailure.Code code) throws Exception {
        store.add(1, "Changed"); store.hashes.put(1L, "preserved");
        if (code == EmbeddingFailure.Code.MODEL_MISMATCH) { doThrow(new EmbeddingFailure(code)).when(client).verifyModel(any()); }
        else { when(client.embed(any(), anyString())).thenThrow(new EmbeddingFailure(code)); }
        var report = run(10);
        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(store.hashes.get(1L)).isEqualTo("preserved");
        assertThat(state().pending()).containsExactly(new AutoEmbeddingState.Range(0, 1));
        assertThat(state().lastSuccessAt()).isNull();
    }

    @Test void busyManualBatchAndDatabaseFailureHaveSafeNonSuccessReports() throws Exception {
        store.busy = true;
        assertThat(run(2).status()).isEqualTo("SKIPPED");
        assertThat(state().latest().code()).isEqualTo("BATCH_BUSY");
        verifyNoInteractions(client);
        store.busy = false; store.databaseFailure = true;
        var failure = run(2);
        assertThat(failure.status()).isEqualTo("FAILED");
        assertThat(failure.code()).isEqualTo("DATABASE");
        assertThat(state().lastSuccessAt()).isNull();
    }

    @Test void emptyAndDeletedRangesCompleteAndNextCycleStartsAtZero() throws Exception {
        assertThat(run(2).status()).isEqualTo("SUCCESS");
        store.add(100, "A"); run(1);
        try (var file = new AutoEmbeddingState(directory)) {
            file.write(new AutoEmbeddingState.Snapshot(1, PerfumeEmbeddingBatchTests.model(), 100,
                    List.of(new AutoEmbeddingState.Range(0, 100)), null, null));
        }
        store.documents.clear();
        assertThat(run(2).result().processed()).isZero();
        assertThat(state().pending()).isEmpty();
        store.add(7, "Lower id after restore/import");
        assertThat(run(2).result().lastId()).isEqualTo(7);
    }

    @Test void checkpointIsExclusiveAtomicAndCorruptionOrIdentityMismatchFailsClosed() throws Exception {
        try (var file = new AutoEmbeddingState(directory)) {
            assertThatThrownBy(() -> new AutoEmbeddingState(directory)).hasMessage("STATE_BUSY");
            file.write(new AutoEmbeddingState.Snapshot(1, PerfumeEmbeddingBatchTests.model(), 10,
                    List.of(new AutoEmbeddingState.Range(0, 10)), null, null));
            assertThat(file.read(PerfumeEmbeddingBatchTests.model()).observedUpperId()).isEqualTo(10);
            assertThatThrownBy(() -> file.read(new EmbeddingModelIdentity("qwen3-embedding:0.6b", "b".repeat(64), "perfume-v1")))
                    .hasMessage("STATE_MODEL_MISMATCH");
        }
        try (var paths = Files.list(directory)) { assertThat(paths.map(p -> p.getFileName().toString())).containsOnly("worker.lock", "state.json"); }
        Files.writeString(directory.resolve("state.json"), "{broken private-password}");
        assertThatThrownBy(() -> run(2)).hasMessage("Automatic embedding stopped: STATE_OR_EXECUTION_ERROR").hasNoCause();
        verifyNoInteractions(client);
    }

    @Test void safeLogsAndStateNeverContainSourceVectorsOrExceptionMessages() throws Exception {
        store.add(1, "PRIVATE-SOURCE-123");
        when(client.embed(any(), anyString())).thenThrow(new IllegalArgumentException("private-password"));
        var logger = (Logger) LoggerFactory.getLogger(AutoPerfumeEmbeddingJob.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            run(2);
            var output = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertThat(output).contains("startedAt=", "finishedAt=", "FAILED", "processed=1", "failed=1", "elapsedMs=")
                    .doesNotContain("PRIVATE-SOURCE", "private-password", "[1.0");
            assertThat(Files.readString(directory.resolve("state.json"))).doesNotContain("PRIVATE-SOURCE", "private-password", "[1.0");
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    static final class Memory implements PerfumeEmbeddingStore {
        final TreeMap<Long, PerfumeEmbeddingDocument> documents = new TreeMap<>();
        final HashMap<Long, String> hashes = new HashMap<>();
        boolean busy, databaseFailure;
        long failSave;
        void add(long id, String name) { documents.put(id, PerfumeEmbeddingBatchTests.document(id, name)); }
        @Override public Session open() {
            if (busy) { throw new EmbeddingFailure(EmbeddingFailure.Code.BATCH_BUSY); }
            if (databaseFailure) { throw new EmbeddingFailure(EmbeddingFailure.Code.DATABASE); }
            return new Session() {
                public Window window(long after) { return new Window(documents.isEmpty() ? 0 : documents.lastKey(), documents.tailMap(after, false).size()); }
                public List<Long> nextIds(long after, long upper, int limit) { return documents.keySet().stream().filter(id -> id > after && id <= upper).limit(limit).toList(); }
                public PerfumeEmbeddingDocument load(long id) { return documents.get(id); }
                public Optional<String> findHash(long id, EmbeddingModelIdentity model) { return Optional.ofNullable(hashes.get(id)); }
                public boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] vector) {
                    if (id == failSave) { throw new EmbeddingFailure(EmbeddingFailure.Code.DATABASE); }
                    hashes.put(id, hash); return true;
                }
                public void close() { }
            };
        }
    }
}
