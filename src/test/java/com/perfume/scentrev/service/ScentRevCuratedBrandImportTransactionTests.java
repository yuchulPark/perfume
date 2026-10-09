package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.service.ScentRevBrandDiscoveryServiceTests.page;
import static com.perfume.scentrev.service.ScentRevBrandPhase1ImportServiceTests.profile;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import com.perfume.domain.Perfume;
import com.perfume.repository.*;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.curated.*;
import com.perfume.scentrev.curated.CuratedBrandDefinition.SourceEntry;
import com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType;
import com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus;

/** Real Spring suspension/commit behavior, existing mapper/importer, mocked repositories; no JDBC/HTTP. */
class ScentRevCuratedBrandImportTransactionTests {
    @Test
    void curatedSnapshotAndStopProgressCommitSeparatelyRemoteCallsSuspendAmbientAndEarlierPerfumesSurvive() {
        var transactions = new RecordingTransactions();
        var store = new CatalogBatchTestStore();
        var progressIds = new ArrayList<Integer>();
        store.databaseCheck = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            progressIds.add(transactions.current);
        };
        var client = mock(ScentRevMcpClient.class);
        var global = mock(ScentRevCatalogBrandDiscoveryService.class);
        var brandRepository = mock(BrandRepository.class);
        var perfumeRepository = mock(PerfumeRepository.class);
        var perfumeIds = new ArrayList<Integer>();
        when(brandRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(perfumeRepository.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            perfumeIds.add(transactions.current);
            Perfume perfume = call.getArgument(0);
            if ("one-b".equals(perfume.getFragranceSlug())) { throw new IllegalStateException("private database diagnostic"); }
            return perfume;
        });
        var mapper = proxy(new ScentRevPhase1ImportService(brandRepository, perfumeRepository,
                mock(PerfumerRepository.class), mock(PerfumePerfumerRepository.class), mock(NoteRepository.class),
                mock(PerfumeNoteRepository.class), mock(AccordRepository.class), mock(PerfumeAccordRepository.class)), transactions);
        when(client.searchFragrancesFiltered("one", 0)).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return page(0, false, "one-a", "one-b");
        });
        when(client.getFragranceProfile(any())).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return profile("one", call.getArgument(0));
        });
        var brandImporter = proxy(new ScentRevBrandPhase1ImportService(new ScentRevBrandDiscoveryService(client), client, mapper), transactions);
        var progress = proxy(new ScentRevCatalogRunProgressService(store.runs, store.brands), transactions);
        var batches = proxy(new ScentRevCatalogBatchImportService(global, progress, brandImporter), transactions);
        var configuration = mock(ScentRevCuratedBrandConfiguration.class);
        when(configuration.load()).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return new CuratedBrandCatalog(List.of("One", "Two"), List.of(parent("one", 1, "One"), parent("two", 2, "Two")));
        });
        var curated = proxy(new ScentRevCuratedBrandImportService(configuration, progress, batches), transactions);
        new TransactionTemplate(transactions).executeWithoutResult(ambient -> {
            Integer ambientId = transactions.current;
            var created = curated.createCuratedRun();
            assertThat(transactions.current).isEqualTo(ambientId);
            var result = curated.processNextBatch(created.run().runId(), 2);
            assertThat(transactions.current).isEqualTo(ambientId);
            assertThat(result.brandsFailedThisBatch()).isEqualTo(1);
            assertThat(result.totalRunPending()).isEqualTo(1);
            assertThat(result.fragrancesProcessedThisBatch()).isEqualTo(1);
            assertThat(result.run().brandsRunning()).isZero();
            assertThat(result.run().batchActive()).isFalse();
            ambient.setRollbackOnly();
        });
        assertThat(perfumeIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(progressIds).isNotEmpty().doesNotContain(1).doesNotContainAnyElementsOf(perfumeIds);
        assertThat(transactions.committed).containsAll(progressIds).contains(perfumeIds.get(0));
        assertThat(transactions.rolledBack).containsExactly(perfumeIds.get(1), 1);
        verifyNoInteractions(global);
        var order = inOrder(client);
        order.verify(client).searchFragrancesFiltered("one", 0);
        order.verify(client).getFragranceProfile("one-a");
        order.verify(client).getFragranceProfile("one-b");
        verifyNoMoreInteractions(client);
    }

    private static CuratedBrandDefinition parent(String slug, int index, String name) {
        return new CuratedBrandDefinition("key-" + index, name, name, slug, true, VerificationStatus.VERIFIED,
                "offline fixture evidence", null, null, List.of(new SourceEntry(index, name, EntryType.BRAND)));
    }
    private static void assertOutsideTransaction(RecordingTransactions transactions) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(transactions.current).isNull();
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, RecordingTransactions transactions) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }
    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final List<Integer> committed = new ArrayList<>();
        private final List<Integer> rolledBack = new ArrayList<>();
        private Integer current;
        private int next;
        @Override protected Object doGetTransaction() { return new Transaction(current); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).id != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            current = ++next;
            ((Transaction) transaction).id = current;
        }
        @Override protected Object doSuspend(Object transaction) {
            Integer suspended = current;
            current = null;
            return suspended;
        }
        @Override protected void doResume(Object transaction, Object suspended) { current = (Integer) suspended; }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed.add(((Transaction) status.getTransaction()).id); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack.add(((Transaction) status.getTransaction()).id); }
        @Override protected void doCleanupAfterCompletion(Object transaction) { current = null; }
        private static class Transaction {
            private Integer id;
            private Transaction(Integer id) { this.id = id; }
        }
    }
}
