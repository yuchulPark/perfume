package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevBrandDiscoveryServiceTests.page;
import static com.perfume.scentrev.service.ScentRevBrandPhase1ImportServiceTests.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.perfume.domain.Perfume;
import com.perfume.repository.*;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;
import com.perfume.scentrev.progress.CatalogImportRunStatus;

/** Real Spring propagation, real orchestration/mapper, mocked repositories; no JDBC or HTTP. */
class ScentRevCatalogBatchImportTransactionTests {
    @Test
    void snapshotProgressAndPerfumesCommitSeparatelyWhileAllRemoteCallsSuspendAmbientTransaction() {
        var transactions = new RecordingTransactions();
        var store = new CatalogBatchTestStore();
        var progressTransactions = new ArrayList<Integer>();
        store.databaseCheck = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            progressTransactions.add(transactions.current);
        };
        var client = mock(ScentRevMcpClient.class);
        var brandRepository = mock(BrandRepository.class);
        var perfumeRepository = mock(PerfumeRepository.class);
        var perfumeTransactions = new ArrayList<Integer>();
        when(brandRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(perfumeRepository.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            perfumeTransactions.add(transactions.current);
            Perfume perfume = call.getArgument(0);
            if ("two-b".equals(perfume.getFragranceSlug())) {
                throw new IllegalStateException("private-sql-diagnostic");
            }
            return perfume;
        });
        var mapper = transactional(new ScentRevPhase1ImportService(brandRepository, perfumeRepository,
                mock(PerfumerRepository.class), mock(PerfumePerfumerRepository.class), mock(NoteRepository.class),
                mock(PerfumeNoteRepository.class), mock(AccordRepository.class), mock(PerfumeAccordRepository.class)), transactions);
        when(client.searchFragrancesFiltered(any(), anyInt())).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            String slug = call.getArgument(0);
            return "two".equals(slug) ? page(0, false, "two-a", "two-b") : page(0, false, slug + "-a");
        });
        when(client.getFragranceProfile(any())).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            String slug = call.getArgument(0);
            return profile(slug.substring(0, slug.indexOf('-')), slug);
        });
        var brandImporter = transactional(new ScentRevBrandPhase1ImportService(
                new ScentRevBrandDiscoveryService(client), client, mapper), transactions);
        var discovery = mock(ScentRevCatalogBrandDiscoveryService.class);
        when(discovery.discoverBrands()).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return new ScentRevCatalogBrandDiscoveryResult(3, 0, 1, List.of(
                    new CatalogBrand("one", "One", 1L), new CatalogBrand("two", "Two", 2L),
                    new CatalogBrand("three", "Three", 1L)));
        });
        var progress = transactional(new ScentRevCatalogRunProgressService(store.runs, store.brands), transactions);
        var batchService = transactional(new ScentRevCatalogBatchImportService(discovery, progress, brandImporter), transactions);

        new TransactionTemplate(transactions).executeWithoutResult(ambient -> {
            Integer ambientId = transactions.current;
            var run = batchService.createRun();
            assertThat(transactions.current).isEqualTo(ambientId);
            var batch = batchService.processNextBatch(run.runId(), 3);
            assertThat(transactions.current).isEqualTo(ambientId);
            assertThat(batch.brandsCompletedThisBatch()).isEqualTo(2);
            assertThat(batch.brandsFailedThisBatch()).isEqualTo(1);
            assertThat(batch.fragrancesProcessedThisBatch()).isEqualTo(3);
            assertThat(batch.run().status()).isEqualTo(CatalogImportRunStatus.COMPLETED_WITH_FAILURES);
            assertThat(batch.run().batchActive()).isFalse();
            assertThat(batch.run().processedFragrances()).isEqualTo(3);
            ambient.setRollbackOnly();
        });

        assertThat(perfumeTransactions).hasSize(4).doesNotHaveDuplicates();
        assertThat(progressTransactions).isNotEmpty().doesNotContainAnyElementsOf(perfumeTransactions).doesNotContain(1);
        assertThat(transactions.committed).containsAll(progressTransactions)
                .contains(perfumeTransactions.get(0), perfumeTransactions.get(1), perfumeTransactions.get(3));
        assertThat(transactions.rolledBack).containsExactly(perfumeTransactions.get(2), 1);
        verify(discovery, times(1)).discoverBrands();
        var order = inOrder(client);
        order.verify(client).searchFragrancesFiltered("one", 0);
        order.verify(client).getFragranceProfile("one-a");
        order.verify(client).searchFragrancesFiltered("two", 0);
        order.verify(client).getFragranceProfile("two-a");
        order.verify(client).getFragranceProfile("two-b");
        order.verify(client).searchFragrancesFiltered("three", 0);
        order.verify(client).getFragranceProfile("three-a");
        verifyNoMoreInteractions(client);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static void assertOutsideTransaction(RecordingTransactions transactions) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(transactions.current).isNull();
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, RecordingTransactions transactions) {
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
