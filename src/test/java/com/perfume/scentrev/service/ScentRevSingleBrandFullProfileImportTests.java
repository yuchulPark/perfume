package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfume.repository.*;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** Mocked network and persistence only; exercises the existing single-brand orchestration. */
class ScentRevSingleBrandFullProfileImportTests {
    @Test
    void fullProfileUsesNoRedundantWearRequestAndKeepsRequestDelay() throws IOException {
        var fixture = new Fixture(); var completedRequests = new ArrayList<Long>();
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenAnswer(call -> {
            completedRequests.add(System.nanoTime()); return page(0, false, "creed-aventus");
        });
        when(fixture.client.getFragranceProfile("creed-aventus")).thenAnswer(call -> {
            assertThat(System.nanoTime() - completedRequests.get(0)).isGreaterThanOrEqualTo(100_000_000L);
            return ScentRevProfileDataServiceTests.fullProfile();
        });
        var result = fixture.service.importBrand(options());
        assertThat(result.completed()).isTrue(); assertThat(result.totalMcpCalls()).isEqualTo(2);
        verify(fixture.client, never()).getWearSummary(anyString());
        verify(fixture.persistence).importPageForSeededBrand(eq(2L), eq("creed"), argThat(profiles -> profiles.get(0).rawResponse() != null));
    }

    @Test
    void missingWearIsFetchedOnceAndAttachedBeforePersistence() throws IOException {
        var fixture = new Fixture(); var full = ScentRevProfileDataServiceTests.fullProfile();
        var sparse = (ObjectNode) full.rawResponse().deepCopy(); sparse.remove("performance");
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false, "creed-aventus"));
        when(fixture.client.getFragranceProfile("creed-aventus")).thenReturn(full.withRawResponse(sparse));
        when(fixture.client.getWearSummary("creed-aventus")).thenReturn(full.rawResponse().path("performance"));
        var result = fixture.service.importBrand(options());
        assertThat(result.totalMcpCalls()).isEqualTo(3); assertThat(result.profilesFetched()).isEqualTo(1);
        var order = inOrder(fixture.client, fixture.persistence);
        order.verify(fixture.client).searchFragrancesFiltered("creed", 0);
        order.verify(fixture.client).getFragranceProfile("creed-aventus");
        order.verify(fixture.client).getWearSummary("creed-aventus");
        order.verify(fixture.persistence).validateProfilePageForBrand(eq("creed"), argThat(profiles -> profiles.get(0).wearSummary() != null));
        order.verify(fixture.persistence).importPageForSeededBrand(eq(2L), eq("creed"), anyList());
        verifyNoMoreInteractions(fixture.client);
    }

    @Test
    void foreignOnlyPageSkipsWearAndPersistenceThenAdvancesNormallyAndDeduplicates() throws IOException {
        var fixture = new Fixture();
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "foreign-one"));
        when(fixture.client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, "foreign-one", "creed-aventus"));
        var foreign = (ObjectNode) ScentRevProfileDataServiceTests.fullProfile().rawResponse().deepCopy();
        foreign.remove(List.of("performance", "accords", "note_pyramid", "reminds_of"));
        ((ObjectNode) foreign.path("identity")).put("public_id", "foreign-id").put("brand_slug", "foreign").put("fragrance_slug", "foreign-one");
        when(fixture.client.getFragranceProfile("foreign-one")).thenReturn(FullProfileTestStore.mapper()
                .treeToValue(foreign, ScentRevFragranceProfileResponse.class).withRawResponse(foreign));
        when(fixture.client.getFragranceProfile("creed-aventus")).thenReturn(ScentRevProfileDataServiceTests.fullProfile());
        var result = fixture.service.importBrand(options());
        assertThat(result.discoveryPages()).isEqualTo(2); assertThat(result.pagesCommitted()).isEqualTo(1);
        assertThat(result.skippedForeignBrand()).isEqualTo(1); assertThat(result.duplicateDiscoveryCount()).isEqualTo(1);
        assertThat(result.perfumesProcessed()).isEqualTo(1);
        var order = inOrder(fixture.client, fixture.persistence);
        order.verify(fixture.client).searchFragrancesFiltered("creed", 0);
        order.verify(fixture.client).getFragranceProfile("foreign-one");
        order.verify(fixture.client).searchFragrancesFiltered("creed", 10);
        order.verify(fixture.client).getFragranceProfile("creed-aventus");
        order.verify(fixture.persistence).validateProfilePageForBrand(eq("creed"), anyList());
        order.verify(fixture.persistence).importPageForSeededBrand(eq(2L), eq("creed"), anyList());
        verify(fixture.client, never()).getWearSummary(anyString()); verifyNoMoreInteractions(fixture.client);
    }

    @Test
    void mismatchedWearIdentityFailsBeforePageWrites() throws IOException {
        var fixture = new Fixture(); var full = ScentRevProfileDataServiceTests.fullProfile();
        var sparse = (ObjectNode) full.rawResponse().deepCopy(); sparse.remove("performance");
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false, "creed-aventus"));
        when(fixture.client.getFragranceProfile("creed-aventus")).thenReturn(full.withRawResponse(sparse));
        when(fixture.client.getWearSummary("creed-aventus")).thenReturn(FullProfileTestStore.mapper().createObjectNode().put("public_id", "different"));
        assertThatThrownBy(() -> fixture.service.importBrand(options())).isInstanceOf(ScentRevSingleBrandImportException.class)
                .hasMessageContaining("identity conflicts");
        verifyNoInteractions(fixture.persistence);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void wearRestrictionsStopImmediatelyWithoutRetryOrFurtherRequests(int status) throws IOException {
        var fixture = new Fixture(); var full = ScentRevProfileDataServiceTests.fullProfile();
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-aventus"));
        when(fixture.client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, true, "creed-second"));
        when(fixture.client.getFragranceProfile("creed-aventus")).thenReturn(full);
        var secondRoot = (ObjectNode) full.rawResponse().deepCopy();
        secondRoot.remove(List.of("performance", "accords", "note_pyramid", "reminds_of"));
        ((ObjectNode) secondRoot.path("identity")).put("public_id", "second-id").put("fragrance_slug", "creed-second");
        when(fixture.client.getFragranceProfile("creed-second")).thenReturn(FullProfileTestStore.mapper()
                .treeToValue(secondRoot, ScentRevFragranceProfileResponse.class).withRawResponse(secondRoot));
        var restriction = mock(ScentRevMcpClientException.class);
        when(restriction.getStatusCode()).thenReturn(status);
        when(restriction.getFailureType()).thenReturn(ScentRevMcpClientException.FailureType.TOOL_CALL);
        when(fixture.client.getWearSummary("creed-second")).thenThrow(restriction);
        var failure = catchThrowableOfType(() -> fixture.service.importBrand(options()), ScentRevSingleBrandImportException.class);
        assertThat(failure.getMessage()).contains("HTTP " + status);
        assertThat(failure.getResult().pagesCommitted()).isEqualTo(1);
        assertThat(failure.getResult().stoppedStage()).isEqualTo("WEAR_SUMMARY");
        verify(fixture.client, times(1)).getWearSummary("creed-second");
        verify(fixture.client, never()).searchFragrancesFiltered("creed", 20);
        verify(fixture.persistence, times(1)).importPageForSeededBrand(eq(2L), eq("creed"), anyList());
    }

    @Test
    void remoteCallsSuspendAmbientTransactionAndNewDataSharesTheExistingPageCommit() throws IOException {
        var transactions = new RecordingTransactions(); var store = new FullProfileTestStore();
        var brandRepo = mock(BrandRepository.class); var perfumeRepo = mock(PerfumeRepository.class);
        when(brandRepo.findByBrandSlug("creed")).thenReturn(Optional.of(store.perfume.getBrand()));
        when(brandRepo.findById(2L)).thenReturn(Optional.of(store.perfume.getBrand()));
        when(perfumeRepo.findByScentrevPublicId("fragrance-aventus")).thenReturn(Optional.of(store.perfume));
        when(perfumeRepo.findByFragranceSlug("creed-aventus")).thenReturn(Optional.of(store.perfume));
        var extension = transactional(store.data, transactions);
        var persistence = transactional(new ScentRevPhase1ImportService(brandRepo, perfumeRepo,
                mock(PerfumerRepository.class), mock(PerfumePerfumerRepository.class), store.noteRepo,
                mock(PerfumeNoteRepository.class), mock(AccordRepository.class), mock(PerfumeAccordRepository.class), extension), transactions);
        var client = mock(ScentRevMcpClient.class); var profile = ScentRevProfileDataServiceTests.fullProfile();
        var root = (ObjectNode) profile.rawResponse().deepCopy(); root.remove(List.of("performance", "perfumers", "note_pyramid", "accords"));
        var sparse = store.mapper.treeToValue(root, ScentRevFragranceProfileResponse.class).withRawResponse(root);
        when(client.searchFragrancesFiltered("creed", 0)).thenAnswer(call -> { outsideTransaction(); return page(0, false, "creed-aventus"); });
        when(client.getFragranceProfile("creed-aventus")).thenAnswer(call -> { outsideTransaction(); return sparse; });
        when(client.getWearSummary("creed-aventus")).thenAnswer(call -> { outsideTransaction(); return profile.rawResponse().path("performance"); });
        var writes = new ArrayList<Integer>();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            writes.add(transactions.current); return call.getArgument(0);
        }).when(store.metricRepo).save(any());
        var service = transactional(new ScentRevSingleBrandImportService(brandRepo, new ScentRevCuratedBrandConfiguration(store.mapper), client, persistence), transactions);
        new TransactionTemplate(transactions).executeWithoutResult(ambient -> {
            assertThat(service.importBrand(options()).perfumesUpdated()).isEqualTo(1);
            assertThat(transactions.current).isEqualTo(1); ambient.setRollbackOnly();
        });
        assertThat(writes).isNotEmpty().containsOnly(2);
        assertThat(transactions.committed).containsExactly(2); assertThat(transactions.rolledBack).containsExactly(1);
    }

    private static void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static ScentRevSingleBrandImportOptions options() { return new ScentRevSingleBrandImportOptions("creed", 100); }
    private static ScentRevFilteredSearchResponse page(int offset, boolean truncated, String... slugs) {
        var rows = java.util.Arrays.stream(slugs).map(slug -> new Fragrance(slug, null, slug, "creed")).toList();
        return new ScentRevFilteredSearchResponse(rows, truncated, offset, 10, rows.size(), false);
    }
    private static class Fixture {
        final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
        final ScentRevPhase1ImportService persistence = mock(ScentRevPhase1ImportService.class);
        final ScentRevSingleBrandImportService service;
        Fixture() {
            var store = new FullProfileTestStore(); var brands = mock(BrandRepository.class);
            when(brands.findByBrandSlug("creed")).thenReturn(Optional.of(store.perfume.getBrand()));
            when(persistence.importPageForSeededBrand(eq(2L), eq("creed"), anyList()))
                    .thenReturn(new ScentRevPhase1PageImportResult(0, 1, 0, Set.of(), Set.of(), Set.of(), 0, 0, 0));
            service = new ScentRevSingleBrandImportService(brands, new ScentRevCuratedBrandConfiguration(store.mapper), client, persistence);
        }
    }
    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, RecordingTransactions transactions) {
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); interceptor.afterPropertiesSet();
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(interceptor); return (T) proxy.getProxy();
    }
    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        final List<Integer> committed = new ArrayList<>(), rolledBack = new ArrayList<>(); Integer current; int next;
        @Override protected Object doGetTransaction() { return new Transaction(current); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).id != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { current = ++next; ((Transaction) transaction).id = current; }
        @Override protected Object doSuspend(Object transaction) { Integer saved = current; current = null; return saved; }
        @Override protected void doResume(Object transaction, Object saved) { current = (Integer) saved; }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed.add(((Transaction) status.getTransaction()).id); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack.add(((Transaction) status.getTransaction()).id); }
        @Override protected void doCleanupAfterCompletion(Object transaction) { current = null; }
        private static class Transaction { Integer id; Transaction(Integer id) { this.id = id; } }
    }
}
