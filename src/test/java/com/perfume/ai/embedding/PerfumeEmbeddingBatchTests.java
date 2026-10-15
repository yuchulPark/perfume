package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PerfumeEmbeddingBatchTests {
    private final PerfumeEmbeddingText texts = new PerfumeEmbeddingText();
    private final MemoryStore store = new MemoryStore();
    private final PerfumeEmbeddingClient client = mock(PerfumeEmbeddingClient.class);
    private final PerfumeEmbeddingBatch batch = new PerfumeEmbeddingBatch(store, client, texts);

    @Test
    void createsChangesSkipsAndKeepsFailedOldEmbeddingWithoutBlockingLaterRows() {
        for (long id = 1; id <= 5; id++) { store.documents.put(id, document(id, "Perfume " + id)); }
        store.hashes.put(new Key(2, model()), "old-2");
        store.hashes.put(new Key(3, model()), texts.hash(texts.render(store.documents.get(3L))));
        store.hashes.put(new Key(4, model()), "old-4");
        when(client.embed(any(), anyString())).thenAnswer(call -> {
            if (call.<String>getArgument(1).contains("Perfume 4")) { throw new EmbeddingFailure(EmbeddingFailure.Code.TIMEOUT); }
            return vector();
        });
        var result = batch.execute(options(1000, 2, 0, model()));
        assertThat(result.available()).isEqualTo(5);
        assertThat(result.target()).isEqualTo(5);
        assertThat(result.processed()).isEqualTo(5);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(store.hashes.get(new Key(4, model()))).isEqualTo("old-4");
        assertThat(store.saved).containsExactly(1L, 2L, 5L);
        assertThat(store.locked).isFalse();
        var rerun = batch.execute(options(1000, 2, 0, model()));
        assertThat(rerun.created()).isZero();
        assertThat(rerun.changed()).isZero();
        assertThat(rerun.skipped()).isEqualTo(4);
        assertThat(rerun.failed()).isEqualTo(1);
        assertThat(store.hashes).hasSize(5);
        verify(client, times(5)).embed(any(), anyString());
    }

    @Test
    void detectsRelationOnlyChangesWithoutTouchingThePublicPerfumeRow() {
        store.documents.put(1L, document(1, "Stored"));
        when(client.embed(any(), anyString())).thenReturn(vector());
        assertThat(batch.execute(options(10, 2, 0, model())).created()).isEqualTo(1);
        var note = new PerfumeEmbeddingDocument.Note("TOP", "Bergamot", 0);
        store.documents.put(1L, new PerfumeEmbeddingDocument(1, "Stored", "Brand", null, null, null, List.of(note), List.of(), List.of()));
        assertThat(batch.execute(options(10, 2, 0, model())).changed()).isEqualTo(1);
        var accord = new PerfumeEmbeddingDocument.Accord("citrus", 85, null, 0);
        store.documents.put(1L, new PerfumeEmbeddingDocument(1, "Stored", "Brand", null, null, null, List.of(note), List.of(accord), List.of()));
        assertThat(batch.execute(options(10, 2, 0, model())).changed()).isEqualTo(1);
        var metric = new PerfumeEmbeddingDocument.Metric("performance.longevity", java.math.BigDecimal.TEN, null, null, null, null, null, null, null, null);
        store.documents.put(1L, new PerfumeEmbeddingDocument(1, "Stored", "Brand", null, null, null, List.of(note), List.of(accord), List.of(metric)));
        assertThat(batch.execute(options(10, 2, 0, model())).changed()).isEqualTo(1);
        assertThat(batch.execute(options(10, 2, 0, model())).skipped()).isEqualTo(1);
        verify(client, times(4)).embed(any(), anyString());
        assertThat(store.hashes).hasSize(1);
    }

    @Test
    void newModelDigestDoesNotReuseOrDeletePreviousModelsEmbedding() {
        store.documents.put(1L, document(1, "Stored"));
        when(client.embed(any(), anyString())).thenReturn(vector());
        batch.execute(options(10, 2, 0, model()));
        var oldHash = store.hashes.get(new Key(1, model()));
        var changedModel = new EmbeddingModelIdentity(model().name(), "b".repeat(64), model().textVersion());
        assertThat(batch.execute(options(10, 2, 0, changedModel)).created()).isEqualTo(1);
        assertThat(store.hashes).hasSize(2);
        assertThat(store.hashes.get(new Key(1, model()))).isEqualTo(oldHash);
        assertThat(batch.execute(options(10, 2, 0, changedModel)).skipped()).isEqualTo(1);
        verify(client, times(2)).embed(any(), anyString());
    }

    @Test
    void keysetPagesAndMaxItemsHaveAnExplicitResumeCursorAndSnapshotUpperBound() {
        for (long id = 1; id <= 5; id++) { store.documents.put(id, document(id, "Stored " + id)); }
        when(client.embed(any(), anyString())).thenAnswer(call -> { store.documents.put(6L, document(6, "New during run")); return vector(); });
        var result = batch.execute(options(2, 1, 0, model()));
        assertThat(result.available()).isEqualTo(5);
        assertThat(result.target()).isEqualTo(2);
        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.lastId()).isEqualTo(2);
        assertThat(result.upperId()).isEqualTo(5);
        assertThat(result.hasMore()).isTrue();
        assertThat(store.saved).containsExactly(1L, 2L);
        assertThat(store.limits).containsOnly(1);
        var resumed = batch.execute(options(10, 2, result.lastId(), model()));
        assertThat(resumed.created()).isEqualTo(4);
        assertThat(resumed.hasMore()).isFalse();
        assertThat(store.saved).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void dimensionMismatchPreservesOldRowAndContinuesWithLaterPerfumes() {
        for (long id = 1; id <= 3; id++) { store.documents.put(id, document(id, "Stored " + id)); }
        store.hashes.put(new Key(1, model()), "old-1");
        store.hashes.put(new Key(2, model()), "old-2");
        when(client.embed(any(), contains("Stored 1"))).thenReturn(new float[1023]);
        when(client.embed(any(), contains("Stored 2"))).thenReturn(vector());
        when(client.embed(any(), contains("Stored 3"))).thenReturn(vector());
        var result = batch.execute(options(10, 2, 0, model()));
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(store.hashes.get(new Key(1, model()))).isEqualTo("old-1");
        assertThat(store.hashes.get(new Key(2, model()))).isEqualTo(texts.hash(texts.render(store.documents.get(2L))));
        assertThat(store.saved).containsExactly(2L, 3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "hash", "reload", "save"})
    void databaseFailureAtAnyItemStageStopsImmediatelyPreservingPriorCommitAndReleasingLock(String stage) {
        for (long id = 1; id <= 3; id++) { store.documents.put(id, document(id, "Stored " + id)); }
        store.hashes.put(new Key(2, model()), "old-2");
        var session = spy(store.open());
        var lockedStore = mock(PerfumeEmbeddingStore.class);
        when(lockedStore.open()).thenReturn(session);
        var databaseFailure = new EmbeddingFailure(EmbeddingFailure.Code.DATABASE);
        switch (stage) {
            case "load" -> doThrow(databaseFailure).when(session).load(2);
            case "hash" -> doThrow(databaseFailure).when(session).findHash(2, model());
            case "reload" -> doReturn(store.documents.get(2L)).doThrow(databaseFailure).when(session).load(2);
            case "save" -> doThrow(databaseFailure).when(session).save(eq(2L), eq(model()), anyString(), anyString(), any(float[].class));
            default -> throw new AssertionError();
        }
        when(client.embed(any(), anyString())).thenReturn(vector());
        var guardedBatch = new PerfumeEmbeddingBatch(lockedStore, client, texts);
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(EmbeddingFailure.class,
                () -> guardedBatch.execute(options(10, 3, 0, model())));

        assertThat(failure.code()).isEqualTo(EmbeddingFailure.Code.DATABASE);
        assertThat(failure).hasNoCause();
        assertThat(failure.result().created()).isEqualTo(1);
        assertThat(failure.result().failed()).isEqualTo(1);
        assertThat(failure.result().processed()).isEqualTo(2);
        assertThat(failure.result().lastId()).isEqualTo(2);
        assertThat(store.saved).containsExactly(1L);
        assertThat(store.hashes.get(new Key(1, model()))).isEqualTo(texts.hash(texts.render(store.documents.get(1L))));
        assertThat(store.hashes.get(new Key(2, model()))).isEqualTo("old-2");
        assertThat(store.hashes).doesNotContainKey(new Key(3, model()));
        verify(session, never()).load(3);
        verify(session, never()).findHash(3, model());
        verify(client, never()).embed(any(), contains("Stored 3"));
        verify(session).close();
        assertThat(store.locked).isFalse();
        reset(client);
        when(client.embed(any(), anyString())).thenReturn(vector());
        var rerun = batch.execute(options(10, 3, 0, model()));
        assertThat(rerun.skipped()).isEqualTo(1);
        assertThat(rerun.changed()).isEqualTo(1);
        assertThat(rerun.created()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = EmbeddingFailure.Code.class, names = {"INVALID_SOURCE", "SOURCE_MISSING"})
    void itemDataErrorsStillFailOnlyThatPerfumeAndContinue(EmbeddingFailure.Code code) {
        for (long id = 1; id <= 3; id++) { store.documents.put(id, document(id, "Stored " + id)); }
        var session = spy(store.open());
        var lockedStore = mock(PerfumeEmbeddingStore.class);
        when(lockedStore.open()).thenReturn(session);
        doThrow(new EmbeddingFailure(code)).when(session).load(2);
        when(client.embed(any(), anyString())).thenReturn(vector());
        var result = new PerfumeEmbeddingBatch(lockedStore, client, texts).execute(options(10, 3, 0, model()));
        assertThat(result.processed()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(2);
        assertThat(store.saved).containsExactly(1L, 3L);
        assertThat(store.locked).isFalse();
    }

    @Test
    void sourceChangedDuringInferenceIsNotSavedAndNextRunCanRetryIt() {
        store.documents.put(1L, document(1, "Before"));
        store.hashes.put(new Key(1, model()), "old");
        when(client.embed(any(), anyString())).thenAnswer(call -> { store.documents.put(1L, document(1, "After")); return vector(); });
        assertThat(batch.execute(options(10, 2, 0, model())).failed()).isEqualTo(1);
        assertThat(store.hashes.get(new Key(1, model()))).isEqualTo("old");
        assertThat(store.saved).isEmpty();
        assertThat(batch.execute(options(10, 2, 0, model())).changed()).isEqualTo(1);
    }

    @Test
    void anAlreadyRunningBatchIsRejectedBeforeAnyOllamaCallAndTheLockIsReleased() {
        try (var ignored = store.open()) {
            assertThatThrownBy(() -> batch.execute(options(10, 2, 0, model())))
                    .isInstanceOf(EmbeddingFailure.class).hasMessageContaining("BATCH_BUSY");
            verifyNoInteractions(client);
        }
        assertThat(batch.execute(options(10, 2, 0, model())).processed()).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void preflightFailureAndInterruptionReleaseTheLockWithoutSaving() {
        store.documents.put(1L, document(1, "Stored"));
        doThrow(new EmbeddingFailure(EmbeddingFailure.Code.MODEL_MISMATCH)).when(client).verifyModel(any());
        assertThatThrownBy(() -> batch.execute(options(10, 2, 0, model()))).hasMessageContaining("MODEL_MISMATCH");
        assertThat(store.locked).isFalse();
        reset(client);
        Thread.currentThread().interrupt();
        try {
            var result = batch.execute(options(10, 2, 0, model()));
            assertThat(result.interrupted()).isTrue();
            assertThat(result.processed()).isZero();
            assertThat(store.saved).isEmpty();
            assertThat(store.locked).isFalse();
        } finally { Thread.interrupted(); }
    }

    @Test
    void laterPageFailureReportsCommittedProgressAndAllowsIdempotentRerun() {
        store.documents.put(1L, document(1, "First"));
        store.documents.put(2L, document(2, "Second"));
        store.failNextAfter = 1;
        when(client.embed(any(), anyString())).thenReturn(vector());
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(EmbeddingFailure.class,
                () -> batch.execute(options(10, 1, 0, model())));
        assertThat(failure.code()).isEqualTo(EmbeddingFailure.Code.DATABASE);
        assertThat(failure.result().processed()).isEqualTo(1);
        assertThat(failure.result().created()).isEqualTo(1);
        assertThat(failure.result().lastId()).isEqualTo(1);
        assertThat(store.saved).containsExactly(1L);
        assertThat(store.locked).isFalse();
        store.failNextAfter = -1;
        var resumed = batch.execute(options(10, 1, 0, model()));
        assertThat(resumed.created()).isEqualTo(1);
        assertThat(resumed.skipped()).isEqualTo(1);
        assertThat(store.saved).containsExactly(1L, 2L);
    }

    static EmbeddingModelIdentity model() { return OllamaPerfumeEmbeddingClientTests.MODEL; }
    static EmbeddingBatchOptions options(int maximum, int page, long after, EmbeddingModelIdentity model) {
        return new EmbeddingBatchOptions(model, URI.create("http://ollama:11434"), page, maximum, after, 0, Duration.ofSeconds(3), Duration.ofSeconds(180));
    }
    static PerfumeEmbeddingDocument document(long id, String name) { return new PerfumeEmbeddingDocument(id, name, "Brand", null, null, null, List.of(), List.of(), List.of()); }
    static float[] vector() { var values = new float[1024]; values[0] = 1; return values; }

    private record Key(long id, EmbeddingModelIdentity model) { }
    private static class MemoryStore implements PerfumeEmbeddingStore {
        final TreeMap<Long, PerfumeEmbeddingDocument> documents = new TreeMap<>();
        final Map<Key, String> hashes = new HashMap<>();
        final List<Long> saved = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        boolean locked;
        long failSave;
        long failNextAfter = -1;
        @Override public Session open() {
            if (locked) { throw new EmbeddingFailure(EmbeddingFailure.Code.BATCH_BUSY); }
            locked = true;
            return new Session() {
                public Window window(long after) { return new Window(documents.isEmpty() ? 0 : documents.lastKey(), documents.tailMap(after, false).size()); }
                public List<Long> nextIds(long after, long upper, int limit) {
                    if (after == failNextAfter) { throw new EmbeddingFailure(EmbeddingFailure.Code.DATABASE); }
                    limits.add(limit);
                    return documents.keySet().stream().filter(id -> id > after && id <= upper).limit(limit).toList();
                }
                public PerfumeEmbeddingDocument load(long id) { return documents.get(id); }
                public Optional<String> findHash(long id, EmbeddingModelIdentity model) { return Optional.ofNullable(hashes.get(new Key(id, model))); }
                public boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] values) {
                    if (id == failSave) { throw new EmbeddingFailure(EmbeddingFailure.Code.DATABASE); }
                    var key = new Key(id, model);
                    if (hash.equals(hashes.get(key))) { return false; }
                    hashes.put(key, hash); saved.add(id); return true;
                }
                public void close() { locked = false; }
            };
        }
    }
}
