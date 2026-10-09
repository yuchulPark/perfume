package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevCatalogPhase1ImportServiceTests.brandResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;
import com.perfume.scentrev.progress.*;

class ScentRevCatalogBatchImportServiceTests {
    private final CatalogBatchTestStore store = new CatalogBatchTestStore();
    private final ScentRevCatalogRunProgressService progress = new ScentRevCatalogRunProgressService(store.runs, store.brands);
    private final ScentRevCatalogBrandDiscoveryService discovery = mock(ScentRevCatalogBrandDiscoveryService.class);
    private final ScentRevBrandPhase1ImportService importer = mock(ScentRevBrandPhase1ImportService.class);
    private final ScentRevCatalogBatchImportService service = new ScentRevCatalogBatchImportService(discovery, progress, importer);

    @Test
    void createRunSnapshotsCanonicalIdentityOrderNullableCountsAndReportedTotalsWithoutImporting() {
        when(discovery.discoverBrands()).thenReturn(new ScentRevCatalogBrandDiscoveryResult(3, 0, 1, List.of(
                new CatalogBrand("creed", "Creed", 12L), new CatalogBrand("unknown", "Unknown", null),
                new CatalogBrand("empty", "Empty", 0L))));
        var run = service.createRun();
        assertThat(run.status()).isEqualTo(CatalogImportRunStatus.PLANNED);
        assertThat(run.totalBrands()).isEqualTo(3);
        assertThat(run.totalReportedFragrances()).isEqualTo(12);
        assertThat(run.brandsWithUnknownCount()).isEqualTo(1);
        assertThat(run.brandsPending()).isEqualTo(3);
        assertThat(run.startedAt()).isNull();
        assertThat(run.createdAt()).isNotNull();
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getBrandSlug).containsExactly("creed", "unknown", "empty");
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getCatalogPosition).containsExactly(1, 2, 3);
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getReportedFragranceCount).containsExactly(12L, null, 0L);
        verifyNoInteractions(importer);
    }

    @Test
    void emptySnapshotIsImmediatelyComplete() {
        when(discovery.discoverBrands()).thenReturn(new ScentRevCatalogBrandDiscoveryResult(0, 0, 1, List.of()));
        var run = service.createRun();
        assertThat(run.runComplete()).isTrue();
        assertThat(run.finishedAt()).isNotNull();
        assertThat(service.processNextBatch(run.runId(), 2).brandsSelected()).isZero();
        verifyNoInteractions(importer);
    }

    @Test
    void invalidSnapshotsFailBeforeAnyWrites() {
        for (var brands : List.of(List.of(new CatalogBrand("bad/slug", "Bad", 1L)),
                List.of(new CatalogBrand("negative", "Negative", -1L)),
                List.of(new CatalogBrand("same", "One", 1L), new CatalogBrand("same", "Two", 2L)))) {
            assertThatThrownBy(() -> progress.saveSnapshot(new ScentRevCatalogBrandDiscoveryResult(brands.size(), 0, 1, brands)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(store.runs);
        assertThat(store.brandRows).isEmpty();
    }

    @Test
    void boundedBatchesSkipCompletedRowsKeepOrderAndNeverRediscoverCatalog() {
        Long runId = plan("one", "two", "three");
        var caller = Thread.currentThread();
        when(importer.importBrand(anyString())).thenAnswer(call -> {
            assertThat(Thread.currentThread()).isSameAs(caller);
            return brandResult(call.getArgument(0), 2, 0);
        });
        var first = service.processNextBatch(runId, 2);
        assertThat(first.brandsSelected()).isEqualTo(2);
        assertThat(first.brandsCompletedThisBatch()).isEqualTo(2);
        assertThat(first.fragrancesProcessedThisBatch()).isEqualTo(4);
        assertThat(first.totalRunCompleted()).isEqualTo(2);
        assertThat(first.totalRunPending()).isEqualTo(1);
        assertThat(first.runComplete()).isFalse();
        assertThat(first.run().batchActive()).isFalse();
        var restartedService = new ScentRevCatalogBatchImportService(discovery,
                new ScentRevCatalogRunProgressService(store.runs, store.brands), importer);
        var second = restartedService.processNextBatch(runId, 2);
        assertThat(second.brandsSelected()).isEqualTo(1);
        assertThat(second.totalRunCompleted()).isEqualTo(3);
        assertThat(second.runComplete()).isTrue();
        assertThat(second.run().processedFragrances()).isEqualTo(6);
        assertThat(second.run().startedAt()).isNotNull();
        assertThat(second.run().finishedAt()).isNotNull();
        var order = inOrder(importer);
        order.verify(importer).importBrand("one");
        order.verify(importer).importBrand("two");
        order.verify(importer).importBrand("three");
        assertThat(service.processNextBatch(runId, 1).brandsSelected()).isZero();
        verifyNoMoreInteractions(importer);
        verify(discovery).discoverBrands();
        verifyNoMoreInteractions(discovery);
    }

    @Test
    void failureRecordsSafePartialProgressContinuesAndExhaustedRunHasFailuresStatus() {
        Long runId = plan("one", "two", "three");
        when(importer.importBrand("one")).thenReturn(brandResult("one", 2, 0));
        when(importer.importBrand("two")).thenThrow(ScentRevBrandImportException.importing(
                ScentRevBrandImportException.Stage.PROFILE, brandResult("two", 5, 0).discovery(), 2, "two-fragrance-2", "Profile failed."));
        when(importer.importBrand("three")).thenReturn(brandResult("three", 1, 0));
        var batch = service.processNextBatch(runId, 3);
        assertThat(batch.brandsCompletedThisBatch()).isEqualTo(2);
        assertThat(batch.brandsFailedThisBatch()).isEqualTo(1);
        assertThat(batch.fragrancesProcessedThisBatch()).isEqualTo(5);
        assertThat(batch.totalRunPending()).isZero();
        assertThat(batch.run().status()).isEqualTo(CatalogImportRunStatus.COMPLETED_WITH_FAILURES);
        assertThat(batch.runComplete()).isFalse();
        assertThat(batch.run().finishedAt()).isNotNull();
        assertThat(store.eligible(runId, CatalogImportBrandStatus.FAILED)).singleElement().satisfies(row -> {
            assertThat(row.getBrandSlug()).isEqualTo("two");
            assertThat(row.getFailureMessage()).contains("PROFILE", "2 processed");
            assertThat(row.getProcessedFragranceCount()).isEqualTo(2);
        });
        verify(importer).importBrand("three");
        assertThat(service.processNextBatch(runId, 3).brandsSelected()).isZero(); // FAILED rows are not automatic retries.
    }

    @Test
    void failedRetryRequeuesOnlyFailedRowsAndReturnsRunToRunningBeforeCompleting() {
        Long runId = plan("one", "two");
        when(importer.importBrand("one")).thenReturn(brandResult("one", 2, 0));
        when(importer.importBrand("two")).thenThrow(new IllegalStateException("fake-secret"))
                .thenReturn(brandResult("two", 3, 0));
        service.processNextBatch(runId, 2);
        var retried = service.retryFailedBrands(runId);
        assertThat(retried.status()).isEqualTo(CatalogImportRunStatus.RUNNING);
        assertThat(retried.brandsPending()).isEqualTo(1);
        assertThat(retried.brandsFailed()).isZero();
        assertThat(retried.finishedAt()).isNull();
        var batch = service.processNextBatch(runId, 1);
        assertThat(batch.runComplete()).isTrue();
        assertThat(batch.run().processedFragrances()).isEqualTo(5); // Latest attempt counts, never double counted.
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getAttemptCount).containsExactly(1, 2);
        verify(importer).importBrand("one");
        verify(importer, times(2)).importBrand("two");
        verifyNoMoreInteractions(importer);
    }

    @Test
    void interruptedRunningRecoveryKeepsCompletedRowsAndRevokesOldExecutor() {
        Long runId = plan("one", "two", "three");
        var claim = progress.claimBatch(runId, 3, null);
        progress.completeBrand(runId, claim.token(), claim.brands().get(0).progressId(), 2);
        assertThatThrownBy(() -> service.processNextBatch(runId, 1)).hasMessageContaining("active batch");
        var recovered = service.recoverInterruptedRun(runId);
        assertThat(recovered.brandsCompleted()).isEqualTo(1);
        assertThat(recovered.brandsPending()).isEqualTo(2);
        assertThat(recovered.brandsRunning()).isZero();
        assertThat(recovered.batchActive()).isFalse();
        assertThatThrownBy(() -> progress.completeBrand(runId, claim.token(), claim.brands().get(1).progressId(), 1))
                .hasMessageContaining("ownership was revoked");
        when(importer.importBrand(anyString())).thenAnswer(call -> brandResult(call.getArgument(0), 1, 0));
        assertThat(service.processNextBatch(runId, 2).runComplete()).isTrue();
        verify(importer, never()).importBrand("one");
        verify(importer).importBrand("two");
        verify(importer).importBrand("three");
    }

    @Test
    void recoveryDoesNotRequeueFailedRowsAndRetryIsRejectedDuringActiveBatch() {
        Long runId = plan("one", "two");
        var claim = progress.claimBatch(runId, 2, null);
        progress.failBrand(runId, claim.token(), claim.brands().get(0).progressId(), 0, "Safe failure.");
        assertThatThrownBy(() -> service.retryFailedBrands(runId)).hasMessageContaining("active batch");
        var recovered = service.recoverInterruptedRun(runId);
        assertThat(recovered.brandsFailed()).isEqualTo(1);
        assertThat(recovered.brandsPending()).isEqualTo(1);
    }

    @Test
    void runningWorkWithoutTokenStillRequiresExplicitRecovery() {
        Long runId = plan("one");
        progress.claimBatch(runId, 1, null);
        store.runRows.get(runId).releaseBatch(java.time.Instant.now());
        assertThatThrownBy(() -> service.processNextBatch(runId, 1)).hasMessageContaining("explicit recovery");
        assertThat(service.recoverInterruptedRun(runId).brandsPending()).isEqualTo(1);
    }

    @Test
    void selectedBatchUsesSnapshotPositionsAndSkipsPreviouslyCompletedBrands() {
        Long runId = plan("large", "small-one", "small-two");
        when(importer.importBrand(anyString())).thenAnswer(call -> brandResult(call.getArgument(0), 1, 0));
        var first = service.processSelectedBatch(runId, List.of("small-two", "small-one"));
        assertThat(first.brandsSelected()).isEqualTo(2);
        var order = inOrder(importer);
        order.verify(importer).importBrand("small-one");
        order.verify(importer).importBrand("small-two");
        assertThat(service.processSelectedBatch(runId, List.of("small-one", "small-two")).brandsSelected()).isZero();
        verify(importer, never()).importBrand("large");
        assertThat(service.getRun(runId).brandsPending()).isEqualTo(1);
    }

    @Test
    void invalidSelectedSlugsDoNotClaimAnything() {
        Long runId = plan("one");
        for (var selected : List.of(List.of("missing"), List.of("one", "one"), List.of("bad/slug"))) {
            assertThatThrownBy(() -> service.processSelectedBatch(runId, selected)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.getRun(runId).batchActive()).isFalse();
        verifyNoInteractions(importer);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    void batchSizeIsBoundedBeforeRepositoryWork(int size) {
        assertThatThrownBy(() -> service.processNextBatch(1L, size)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store.runs, importer, discovery);
    }

    @Test
    void twoRunsKeepIndependentSnapshotsAndCounters() {
        Long first = plan("one");
        Long second = plan("two");
        when(importer.importBrand("two")).thenReturn(brandResult("two", 1, 0));
        service.processNextBatch(second, 1);
        assertThat(service.getRun(first).brandsPending()).isEqualTo(1);
        assertThat(service.getRun(second).runComplete()).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void unknownOrInvalidRunFailsWithoutMcpCalls() {
        assertThatThrownBy(() -> service.processNextBatch(42L, 1)).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> service.getRun(-1L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(importer, discovery);
    }

    @Test
    void rawExceptionSecretsAreNeverPersisted() {
        Long runId = plan("one");
        when(importer.importBrand("one")).thenThrow(new IllegalStateException("Authorization Bearer fake-secret DB_PASSWORD",
                new IllegalArgumentException("fake-private-payload")));
        var batch = service.processNextBatch(runId, 1);
        assertThat(batch.failedBrands().toString()).doesNotContain("fake-secret", "Authorization", "DB_PASSWORD", "fake-private-payload");
        assertThat(store.brandRows.values()).singleElement().satisfies(row ->
                assertThat(row.getFailureMessage()).doesNotContain("fake-secret", "Authorization", "DB_PASSWORD", "fake-private-payload"));
    }

    @Test
    void interruptedThreadStopsBeforeClaimOrBeforeNextBrandAndRecoveryRequeuesUnfinishedWork() {
        Long runId = plan("one", "two");
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> service.processNextBatch(runId, 2)).hasMessageContaining("interrupted");
            assertThat(store.eligible(runId, CatalogImportBrandStatus.PENDING)).hasSize(2);
            Thread.interrupted();
            when(importer.importBrand("one")).thenAnswer(call -> {
                Thread.currentThread().interrupt();
                return brandResult("one", 1, 0);
            });
            assertThatThrownBy(() -> service.processNextBatch(runId, 2)).hasMessageContaining("interrupted");
        } finally { Thread.interrupted(); }
        assertThat(service.getRun(runId).brandsCompleted()).isEqualTo(1);
        assertThat(service.getRun(runId).batchActive()).isTrue();
        assertThat(service.recoverInterruptedRun(runId).brandsPending()).isEqualTo(1);
        verify(importer, never()).importBrand("two");
    }

    @Test
    void progressDatabaseFailurePropagatesRatherThanBeingRecordedAsAProviderFailure() {
        Long runId = plan("one", "two");
        var failingProgress = spy(progress);
        doThrow(new IllegalStateException("Progress database unavailable.")).when(failingProgress)
                .completeBrand(anyLong(), anyString(), anyLong(), anyLong());
        when(importer.importBrand("one")).thenReturn(brandResult("one", 1, 0));
        var batcher = new ScentRevCatalogBatchImportService(discovery, failingProgress, importer);
        assertThatThrownBy(() -> batcher.processNextBatch(runId, 2)).hasMessageContaining("Progress database unavailable");
        assertThat(service.getRun(runId).brandsRunning()).isEqualTo(2);
        assertThat(service.getRun(runId).batchActive()).isTrue();
        verify(failingProgress, never()).failBrand(anyLong(), anyString(), anyLong(), anyLong(), anyString());
        verify(importer, never()).importBrand("two");
    }

    @Test
    void inconsistentBrandResultIsFailedAndAnEmptyBrandStillCompletes() {
        Long runId = plan("wrong", "empty");
        when(importer.importBrand("wrong")).thenReturn(brandResult("different", 1, 0));
        when(importer.importBrand("empty")).thenReturn(brandResult("empty", 0, 0));
        var batch = service.processNextBatch(runId, 2);
        assertThat(batch.brandsFailedThisBatch()).isEqualTo(1);
        assertThat(batch.brandsCompletedThisBatch()).isEqualTo(1);
        assertThat(batch.failedBrands().get(0).message()).contains("INVALID_BRAND_RESULT");
    }

    private Long plan(String... slugs) {
        var rows = IntStream.range(0, slugs.length).mapToObj(i -> new CatalogBrand(slugs[i], "Name " + slugs[i], 2L)).toList();
        when(discovery.discoverBrands()).thenReturn(new ScentRevCatalogBrandDiscoveryResult(rows.size(), 0, 1, rows));
        return service.createRun().runId();
    }
}
