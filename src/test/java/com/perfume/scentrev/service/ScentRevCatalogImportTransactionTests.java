package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevCatalogBrandDiscoveryServiceTests.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;

/** Same lightweight strategy as the Creed transaction test; all three production layers use Spring proxies. */
class ScentRevCatalogImportTransactionTests {

    @Test
    void catalogSuspendsAmbientTransactionAndPreservesIndependentCommitsAcrossBrandFailure() {
        var transactions = new RecordingTransactions();
        var client = mock(ScentRevMcpClient.class);
        var brandRepository = mock(BrandRepository.class);
        var perfumeRepository = mock(PerfumeRepository.class);
        when(brandRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(perfumeRepository.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Perfume perfume = call.getArgument(0);
            if ("two-fragrance-1".equals(perfume.getFragranceSlug())) {
                throw new IllegalStateException("fake-private-sql-diagnostic");
            }
            return perfume;
        });
        var mapper = transactional(new ScentRevPhase1ImportService(brandRepository, perfumeRepository,
                mock(PerfumerRepository.class), mock(PerfumePerfumerRepository.class), mock(NoteRepository.class),
                mock(PerfumeNoteRepository.class), mock(AccordRepository.class), mock(PerfumeAccordRepository.class)), transactions);
        when(client.listBrands(0)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return page(0, false, "one", "two", "three");
        });
        when(client.searchFragrancesFiltered(anyString(), anyInt())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String slug = call.getArgument(0);
            assertThat((Integer) call.getArgument(1)).isZero();
            var fragrances = new ArrayList<Fragrance>();
            fragrances.add(new Fragrance(slug + "-fragrance-0", "id-" + slug + "-fragrance-0", "Name", slug));
            if ("two".equals(slug)) {
                fragrances.add(new Fragrance("two-fragrance-1", "id-two-fragrance-1", "Name", slug));
            }
            return new ScentRevFilteredSearchResponse(fragrances, false, 0, 10, fragrances.size(), null);
        });
        when(client.getFragranceProfile(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String slug = call.getArgument(0);
            String brand = slug.substring(0, slug.indexOf("-fragrance-"));
            return new ScentRevFragranceProfileResponse(new ScentRevIdentity("Name", "id-" + slug,
                    "Brand name", brand, slug, null, null, null, null, null), null, null, null, null, null, null, null);
        });
        var brandImporter = transactional(new ScentRevBrandPhase1ImportService(
                new ScentRevBrandDiscoveryService(client), client, mapper), transactions);
        var catalogImporter = transactional(new ScentRevCatalogPhase1ImportService(
                new ScentRevCatalogBrandDiscoveryService(client), brandImporter), transactions);

        new TransactionTemplate(transactions).executeWithoutResult(ambient -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var result = catalogImporter.importCatalog();
            assertThat(result.brandsSucceeded()).isEqualTo(2);
            assertThat(result.brandsFailed()).isEqualTo(1);
            assertThat(result.totalFragrancesDiscovered()).isEqualTo(4);
            assertThat(result.totalFragrancesProcessed()).isEqualTo(3);
            assertThat(result.failedBrands()).singleElement().satisfies(failure -> {
                assertThat(failure.brandSlug()).isEqualTo("two");
                assertThat(failure.errorCategory()).isEqualTo("PERSISTENCE");
                assertThat(failure.successfullyProcessedCount()).isEqualTo(1);
                assertThat(failure.message()).doesNotContain("fake-private-sql-diagnostic");
            });
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ambient.setRollbackOnly();
        });

        assertThat(transactions.events).containsExactly("begin-1", "suspend-1", "begin-2", "commit-2",
                "begin-3", "commit-3", "begin-4", "rollback-4", "begin-5", "commit-5", "resume-1", "rollback-1");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(client).searchFragrancesFiltered("three", 0);
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
        private final List<String> events = new ArrayList<>();
        private Integer current;
        private int next;

        @Override
        protected Object doGetTransaction() { return new Transaction(current); }
        @Override
        protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).id != null; }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            current = ++next;
            ((Transaction) transaction).id = current;
            events.add("begin-" + current);
        }
        @Override
        protected Object doSuspend(Object transaction) {
            Integer suspended = current;
            events.add("suspend-" + suspended);
            current = null;
            return suspended;
        }
        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            current = (Integer) suspendedResources;
            events.add("resume-" + current);
        }
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit-" + ((Transaction) status.getTransaction()).id);
        }
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback-" + ((Transaction) status.getTransaction()).id);
        }
        @Override
        protected void doCleanupAfterCompletion(Object transaction) { current = null; }

        private static class Transaction {
            private Integer id;
            private Transaction(Integer id) { this.id = id; }
        }
    }
}
