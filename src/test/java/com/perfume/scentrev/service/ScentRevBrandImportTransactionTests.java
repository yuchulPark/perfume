package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevBrandDiscoveryServiceTests.page;
import static com.perfume.scentrev.service.ScentRevBrandPhase1ImportServiceTests.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

/** Real Spring transaction interceptors and real mapper, with in-memory transaction/repository boundaries. */
class ScentRevBrandImportTransactionTests {

    @Test
    void earlierMapperCommitSurvivesLaterFailureAndEvenAmbientTransactionRollback() {
        var transactions = new RecordingTransactions();
        var client = mock(ScentRevMcpClient.class);
        var brandRepository = mock(BrandRepository.class);
        var perfumeRepository = mock(PerfumeRepository.class);
        when(brandRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(perfumeRepository.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Perfume perfume = call.getArgument(0);
            if ("creed-b".equals(perfume.getFragranceSlug())) {
                throw new IllegalStateException("fake-private-sql-diagnostic");
            }
            return perfume;
        });
        var mapper = transactional(new ScentRevPhase1ImportService(brandRepository, perfumeRepository,
                mock(PerfumerRepository.class), mock(PerfumePerfumerRepository.class), mock(NoteRepository.class),
                mock(PerfumeNoteRepository.class), mock(AccordRepository.class), mock(PerfumeAccordRepository.class)), transactions);
        when(client.searchFragrancesFiltered("creed", 0)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return page(0, false, "creed-a", "creed-b", "creed-c");
        });
        when(client.getFragranceProfile(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return profile("creed", call.getArgument(0));
        });
        var service = transactional(new ScentRevBrandPhase1ImportService(
                new ScentRevBrandDiscoveryService(client), client, mapper), transactions);

        new TransactionTemplate(transactions).executeWithoutResult(ambient -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> service.importBrand("creed")).isInstanceOf(ScentRevBrandImportException.class)
                    .hasMessageContaining("fragrance 2/3").hasMessageContaining("1 successfully processed")
                    .hasMessageNotContaining("fake-private-sql-diagnostic").hasNoCause();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ambient.setRollbackOnly();
        });

        assertThat(transactions.events).containsExactly("begin-1", "suspend-1", "begin-2", "commit-2",
                "begin-3", "rollback-3", "resume-1", "rollback-1");
        verify(client, never()).getFragranceProfile("creed-c");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
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
