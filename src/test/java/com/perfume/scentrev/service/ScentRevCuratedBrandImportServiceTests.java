package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.service.ScentRevCatalogPhase1ImportServiceTests.brandResult;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.curated.*;
import com.perfume.scentrev.curated.CuratedBrandDefinition.SourceEntry;
import com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType;
import com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.progress.*;

class ScentRevCuratedBrandImportServiceTests {
    private final CatalogBatchTestStore store = new CatalogBatchTestStore();
    private final ScentRevCatalogRunProgressService progress = new ScentRevCatalogRunProgressService(store.runs, store.brands);
    private final ScentRevCatalogBrandDiscoveryService globalDiscovery = mock(ScentRevCatalogBrandDiscoveryService.class);
    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevBrandPhase1ImportService importer = mock(ScentRevBrandPhase1ImportService.class);
    private final ScentRevCatalogBatchImportService batches = new ScentRevCatalogBatchImportService(globalDiscovery, progress, importer);
    private final ScentRevCuratedBrandConfiguration configuration = mock(ScentRevCuratedBrandConfiguration.class);
    private final ScentRevCuratedBrandImportService service = new ScentRevCuratedBrandImportService(configuration, progress, batches);

    @Test
    void realResourceSnapshotsOnlyItsCurrentlyApprovedParentsWithoutFixedRunSizeOrRemoteDiscovery() {
        var loaded = new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load();
        when(configuration.load()).thenReturn(loaded);
        var created = service.createCuratedRun();
        assertThat(created.catalog().rawLabels()).hasSize(187);
        assertThat(created.run().totalBrands()).isEqualTo(loaded.verifiedBrands().size());
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getBrandSlug)
                .containsExactlyElementsOf(loaded.verifiedBrands().stream().map(brand -> brand.brandSlug()).toList());
        verifyNoInteractions(globalDiscovery, client, importer);
    }

    @Test
    void snapshotsOnlyVerifiedEnabledDistinctSlugsInConfiguredOrderWithoutCollectionRows() {
        when(configuration.load()).thenReturn(testCatalog());
        var run = service.createCuratedRun().run();
        assertThat(run.totalBrands()).isEqualTo(2);
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getBrandSlug).containsExactly("one", "two");
        assertThat(store.brandRows.values()).extracting(CatalogImportBrandProgress::getCatalogPosition).containsExactly(1, 2);
        verifyNoInteractions(globalDiscovery, client, importer);
    }

    @Test
    void noVerifiedParentsFailBeforeWritingAnything() {
        when(configuration.load()).thenReturn(new CuratedBrandCatalog(List.of("Unknown"), List.of(
                definition("u", "Unknown", null, true, VerificationStatus.UNRESOLVED, 1))));
        assertThatThrownBy(service::createCuratedRun).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store.runs, globalDiscovery, client, importer);
        assertThat(store.brandRows).isEmpty();
    }

    @Test
    void reusesExistingBatchSkipsCompletedAndNeverReloadsOrExpandsFrozenScope() {
        var run = createTestRun();
        when(importer.importBrand("one")).thenReturn(brandResult("one", 2, 0));
        when(importer.importBrand("two")).thenReturn(brandResult("two", 3, 0));
        var first = service.processNextBatch(run.runId(), 1);
        assertThat(first.totalRunCompleted()).isEqualTo(1);
        assertThat(first.totalRunPending()).isEqualTo(1);
        // Later configuration changes affect future runs only.
        when(configuration.load()).thenThrow(new IllegalStateException("must not reload existing scope"));
        assertThat(service.processNextBatch(run.runId(), 1).runComplete()).isTrue();
        assertThat(service.processNextBatch(run.runId(), 1).brandsSelected()).isZero();
        var order = inOrder(importer);
        order.verify(importer).importBrand("one");
        order.verify(importer).importBrand("two");
        verifyNoMoreInteractions(importer);
        verify(configuration).load();
        verifyNoInteractions(globalDiscovery, client);
        assertThat(progress.getRun(run.runId()).processedFragrances()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHENTICATION", "HTTP 429", "rpc_timeout", "policy/access"})
    void providerFailureStopsBeforeNextBrandReleasesReservationsAndNeedsExplicitRetry(String providerError) {
        var run = createTestRun();
        when(importer.importBrand("one")).thenThrow(new IllegalStateException(providerError + " private credentials"));
        var failed = service.processNextBatch(run.runId(), 2);
        assertThat(failed.brandsFailedThisBatch()).isEqualTo(1);
        assertThat(failed.totalRunPending()).isEqualTo(1);
        assertThat(failed.run().brandsRunning()).isZero();
        assertThat(failed.run().batchActive()).isFalse();
        assertThat(failed.failedBrands().get(0).message()).doesNotContain("credentials", providerError);
        verify(importer).importBrand("one");
        verifyNoMoreInteractions(importer);
        assertThat(progress.retryFailedBrands(run.runId()).brandsPending()).isEqualTo(2);
        doReturn(brandResult("one", 1, 0)).when(importer).importBrand("one");
        when(importer.importBrand("two")).thenReturn(brandResult("two", 1, 0));
        assertThat(service.processNextBatch(run.runId(), 2).runComplete()).isTrue();
        verifyNoInteractions(globalDiscovery, client);
    }

    @Test
    void partialBrandFailureRecordsEarlierPerfumeCommitsWhileNextBrandStaysPending() {
        var run = createTestRun();
        var discovery = new ScentRevBrandDiscoveryResult("one", 1, 2, 0, List.of(
                new com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance("one-a", "id-a", "A", "one"),
                new com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance("one-b", "id-b", "B", "one")));
        when(importer.importBrand("one")).thenThrow(ScentRevBrandImportException.importing(
                ScentRevBrandImportException.Stage.PROFILE, discovery, 1, "one-b", "Safe provider failure"));
        var failed = service.processNextBatch(run.runId(), 2);
        assertThat(failed.fragrancesProcessedThisBatch()).isEqualTo(1);
        assertThat(failed.run().processedFragrances()).isEqualTo(1);
        assertThat(failed.totalRunPending()).isEqualTo(1);
        assertThat(failed.failedBrands().get(0).message()).contains("PROFILE");
        verify(importer).importBrand("one");
        verifyNoMoreInteractions(importer);
    }

    @Test
    void interruptedRunRecoveryUsesExistingFencingAndLeavesCompletedWorkAlone() {
        var run = createTestRun();
        var claim = progress.claimBatch(run.runId(), 2, null);
        progress.completeBrand(run.runId(), claim.token(), claim.brands().get(0).progressId(), 2);
        var recovered = progress.recoverInterruptedRun(run.runId());
        assertThat(recovered.brandsCompleted()).isEqualTo(1);
        assertThat(recovered.brandsPending()).isEqualTo(1);
        assertThat(recovered.processedFragrances()).isEqualTo(2);
        assertThatThrownBy(() -> progress.stopBatch(run.runId(), claim.token())).isInstanceOf(IllegalStateException.class);
        when(importer.importBrand("two")).thenReturn(brandResult("two", 1, 0));
        assertThat(service.processNextBatch(run.runId(), 1).runComplete()).isTrue();
        verify(importer).importBrand("two");
        verifyNoMoreInteractions(importer);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 6, 100})
    void conservativeBatchCapFailsBeforeClaimOrImport(int size) {
        assertThatThrownBy(() -> service.processNextBatch(1L, size)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store.runs, globalDiscovery, client, importer);
    }

    private ScentRevCatalogRunSummary createTestRun() {
        when(configuration.load()).thenReturn(testCatalog());
        return service.createCuratedRun().run();
    }

    private CuratedBrandCatalog testCatalog() {
        return new CuratedBrandCatalog(List.of("One", "One exclusive", "One alias", "Two", "Unknown", "Disabled"), List.of(
                new CuratedBrandDefinition("one", "One", "One", "one", true, VerificationStatus.VERIFIED, "offline fixture evidence", 2L, null,
                        List.of(new SourceEntry(1,"One",EntryType.BRAND), new SourceEntry(2,"One exclusive",EntryType.COLLECTION))),
                definition("alias", "One alias", "one", true, VerificationStatus.VERIFIED, 3),
                definition("two", "Two", "two", true, VerificationStatus.VERIFIED, 4),
                definition("unknown", "Unknown", null, true, VerificationStatus.UNRESOLVED, 5),
                definition("disabled", "Disabled", "disabled", false, VerificationStatus.DISABLED, 6)));
    }

    private CuratedBrandDefinition definition(String key, String label, String slug, boolean enabled, VerificationStatus status, int index) {
        return new CuratedBrandDefinition(key, label, label, slug, enabled, status, slug == null ? null : "offline fixture evidence", null, null,
                List.of(new SourceEntry(index, label, EntryType.BRAND)));
    }
}
