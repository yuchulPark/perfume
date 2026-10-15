package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.service.ScentRevSkipExistingImportTests.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.domain.Perfume;
import com.perfume.repository.*;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring transaction propagation and persistence code, with mocked repositories; no JDBC. */
class ScentRevSkipExistingTransactionTests {
    @Test
    void readonlyPageLookupAndWritePageCommitAreSeparateAndAllApiCallsSuspendAmbientTransactions() {
        var tx = new Transactions(); var fixture = new Fixture(); fixture.catalog(2, 1);
        when(fixture.brands.findById(2L)).thenReturn(Optional.of(fixture.brand));
        when(fixture.perfumes.findImportIdentities(anyCollection(), anyCollection())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return fixture.identities;
        });
        var writes = new ArrayList<Integer>();
        when(fixture.perfumes.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            writes.add(tx.current); return call.getArgument(0);
        });
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenAnswer(call -> {
            outsideTransaction(); return page(0, false, new Fragrance("item-0", "id-item-0", "Name", "creed"),
                    new Fragrance("item-1", "id-item-1", "Name", "creed"));
        });
        when(fixture.client.getFragranceProfile("item-1")).thenAnswer(call -> {
            outsideTransaction(); return profile("item-1", "id-item-1", "creed", true);
        });
        var persistence = transactional(persistence(fixture.brands, fixture.perfumes), tx);
        var lookup = transactional(fixture.lookup, tx);
        var service = transactional(new ScentRevSingleBrandImportService(fixture.brands,
                new ScentRevCuratedBrandConfiguration(new ObjectMapper()), fixture.client, persistence, lookup), tx);
        new TransactionTemplate(tx).executeWithoutResult(ambient -> {
            var result = service.importBrand(options(true));
            assertThat(result.existingSkipped()).isEqualTo(1); assertThat(result.perfumesInserted()).isEqualTo(1);
            assertThat(result.perfumesUpdated()).isZero(); assertThat(result.pagesCommitted()).isEqualTo(1);
            assertThat(tx.current).isEqualTo(1); ambient.setRollbackOnly();
        });
        assertThat(writes).containsExactly(3);
        assertThat(tx.readOnly).containsEntry(2, true).containsEntry(3, false);
        assertThat(tx.committed).containsExactly(2, 3); assertThat(tx.rolledBack).containsExactly(1);
    }

    @Test
    void insertOnlyGuardRollsBackBeforeChangingExistingFieldsRelationsOrProviderSnapshots() throws Exception {
        var tx = new Transactions(); var store = new FullProfileTestStore();
        var brands = mock(BrandRepository.class); var perfumes = mock(PerfumeRepository.class);
        when(brands.findById(2L)).thenReturn(Optional.of(store.perfume.getBrand()));
        when(perfumes.findByScentrevPublicId("fragrance-aventus")).thenReturn(Optional.of(store.perfume));
        when(perfumes.findByFragranceSlug("creed-aventus")).thenReturn(Optional.of(store.perfume));
        var service = transactional(new ScentRevPhase1ImportService(brands, perfumes, mock(PerfumerRepository.class),
                mock(PerfumePerfumerRepository.class), store.noteRepo, mock(PerfumeNoteRepository.class), mock(AccordRepository.class),
                mock(PerfumeAccordRepository.class), store.data), tx);
        assertThatThrownBy(() -> service.importPageForSeededBrand(2L, "creed", List.of(ScentRevProfileDataServiceTests.fullProfile()), true))
                .isInstanceOf(ScentRevPhase1ImportService.IdentityConflictException.class).hasMessageContaining("no existing data may be updated");
        assertThat(store.perfume.getName()).isEqualTo("Aventus");
        assertThat(store.perfume.getDescription()).isEqualTo("Original description");
        assertThat(store.perfume.getReleaseYear()).isEqualTo(2010);
        assertThat(store.perfume.getReviewsCount()).isEqualTo(26169L);
        verify(perfumes, never()).save(any()); verify(brands, never()).save(any());
        verifyNoInteractions(store.metricRepo, store.opinionRepo, store.similarityRepo, store.payloadRepo, store.flatRepo, store.noteRepo);
        assertThat(store.payloads).isEmpty(); assertThat(store.metrics).isEmpty();
        assertThat(tx.committed).isEmpty(); assertThat(tx.rolledBack).containsExactly(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLateExistingRowOrResolvedRenameAbortsOnlyTheCurrentPageAndRetainsThePreviousCommit(boolean renamedSlug) {
        var tx = new Transactions(); var fixture = new Fixture();
        when(fixture.brands.findById(2L)).thenReturn(Optional.of(fixture.brand));
        var old = new Perfume("late-id", renamedSlug ? "old-slug" : "late", "Preserved name", 2020, "Preserved text", 1L, fixture.brand);
        ReflectionTestUtils.setField(old, "id", 99L);
        when(fixture.perfumes.findByScentrevPublicId("late-id")).thenReturn(Optional.of(old));
        if (!renamedSlug) { when(fixture.perfumes.findByFragranceSlug("late")).thenReturn(Optional.of(old)); }
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, new Fragrance("fresh", "fresh-id", "Fresh", "creed")));
        when(fixture.client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, new Fragrance("late", null, "Late", "creed")));
        when(fixture.client.getFragranceProfile("fresh")).thenAnswer(call -> { outsideTransaction(); return profile("fresh", "fresh-id", "creed", true); });
        when(fixture.client.getFragranceProfile("late")).thenAnswer(call -> { outsideTransaction(); return profile("late", "late-id", "creed", true); });
        var saved = new ArrayList<String>();
        when(fixture.perfumes.save(any())).thenAnswer(call -> { Perfume row = call.getArgument(0); saved.add(row.getFragranceSlug()); return row; });
        var service = transactional(new ScentRevSingleBrandImportService(fixture.brands,
                new ScentRevCuratedBrandConfiguration(new ObjectMapper()), fixture.client,
                transactional(persistence(fixture.brands, fixture.perfumes), tx), transactional(fixture.lookup, tx)), tx);
        var failure = catchThrowableOfType(ScentRevSingleBrandImportException.class, () -> service.importBrand(options(true)));
        assertThat(failure.getResult().stoppedStage()).isEqualTo("PERSISTENCE");
        assertThat(failure.getResult().pagesCommitted()).isEqualTo(1); assertThat(failure.getResult().perfumesInserted()).isEqualTo(1);
        assertThat(failure.getResult().perfumesUpdated()).isZero(); assertThat(failure.getResult().totalMcpCalls()).isEqualTo(4);
        assertThat(saved).containsExactly("fresh");
        assertThat(old.getName()).isEqualTo("Preserved name"); assertThat(old.getDescription()).isEqualTo("Preserved text");
        assertThat(old.getFragranceSlug()).isEqualTo(renamedSlug ? "old-slug" : "late");
        assertThat(tx.committed).containsExactly(1, 2, 3); // read page 1, write page 1, read page 2
        assertThat(tx.rolledBack).containsExactly(4); // write page 2 only
        verify(fixture.client, never()).getWearSummary(anyString());
    }

    private static ScentRevPhase1ImportService persistence(BrandRepository brands, PerfumeRepository perfumes) {
        return new ScentRevPhase1ImportService(brands, perfumes, mock(PerfumerRepository.class), mock(PerfumePerfumerRepository.class),
                mock(NoteRepository.class), mock(PerfumeNoteRepository.class), mock(AccordRepository.class), mock(PerfumeAccordRepository.class));
    }
    private static void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, Transactions transactions) {
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); interceptor.afterPropertiesSet();
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(interceptor); return (T) proxy.getProxy();
    }
    private static class Transactions extends AbstractPlatformTransactionManager {
        final List<Integer> committed = new ArrayList<>(), rolledBack = new ArrayList<>();
        final Map<Integer, Boolean> readOnly = new HashMap<>(); Integer current; int next;
        @Override protected Object doGetTransaction() { return new Transaction(current); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).id != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { current = ++next; ((Transaction) transaction).id = current; readOnly.put(current, definition.isReadOnly()); }
        @Override protected Object doSuspend(Object transaction) { Integer saved = current; current = null; return saved; }
        @Override protected void doResume(Object transaction, Object saved) { current = (Integer) saved; }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed.add(((Transaction) status.getTransaction()).id); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack.add(((Transaction) status.getTransaction()).id); }
        @Override protected void doCleanupAfterCompletion(Object transaction) { current = null; }
        private static class Transaction { Integer id; Transaction(Integer id) { this.id = id; } }
    }
}
