package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.OnDemandTestStore.profile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.perfume.domain.Perfume;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevSearchResponse;

/** Actual Spring interceptors and mapper with a recording transaction manager; no JDBC. */
class ScentRevOnDemandTransactionTests {
    @Test
    void remoteSearchAndProfileHaveNoTransactionAndSelectedMapperCommitSurvivesAmbientRollback() {
        var transactions = new RecordingTransactions();
        var store = new OnDemandTestStore();
        var dbTransactions = new ArrayList<Integer>();
        var writes = new ArrayList<Integer>();
        store.databaseCheck = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            dbTransactions.add(transactions.current);
        };
        when(store.perfumes.searchCached(anyString(), any())).thenAnswer(call -> {
            store.databaseCheck.run();
            return List.of();
        });
        when(store.perfumes.save(any(Perfume.class))).thenAnswer(call -> {
            store.databaseCheck.run();
            writes.add(transactions.current);
            assertThat(transactions.readOnly.get(transactions.current)).isFalse();
            Perfume perfume = call.getArgument(0);
            ReflectionTestUtils.setField(perfume, "id", 100L);
            store.perfumeRows.put(perfume.getFragranceSlug(), perfume);
            return perfume;
        });
        var client = mock(ScentRevMcpClient.class);
        when(client.searchFragrances("Selected", 5)).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return new ScentRevSearchResponse(List.of());
        });
        when(client.getFragranceProfile("creed-selected")).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return profile("creed-selected", "id-selected");
        });
        var mapper = transactional(store.mapper, transactions);
        var cache = transactional(new ScentRevOnDemandCacheAccess(store.perfumes, mapper), transactions);
        var service = transactional(new ScentRevOnDemandPerfumeService(client, cache), transactions);
        new TransactionTemplate(transactions).executeWithoutResult(ambient -> {
            assertThat(service.search("Selected", 5).candidates()).isEmpty();
            assertThat(transactions.current).isEqualTo(1);
            var imported = service.getOrImportBySlug("creed-selected");
            assertThat(imported.getId()).isEqualTo(100L);
            assertThat(service.getOrImportBySlug("creed-selected")).isSameAs(imported);
            assertThat(transactions.current).isEqualTo(1);
            ambient.setRollbackOnly();
        });
        assertThat(writes).hasSize(1).doesNotContain(1);
        assertThat(transactions.committed).containsAll(dbTransactions).containsAll(writes);
        assertThat(transactions.rolledBack).containsExactly(1);
        verify(client).searchFragrances("Selected", 5);
        verify(client).getFragranceProfile("creed-selected");
        verifyNoMoreInteractions(client);
        verify(store.mapper, times(1)).importProfile(any());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void identityRaceDetectedAfterMapperRollsBackTheEntireSelectedPerfumeTransaction() {
        var transactions = new RecordingTransactions();
        var store = new OnDemandTestStore();
        var unrelated = store.remember("other-fragrance", "other-public-id");
        var returnedProfile = profile("creed-selected", "id-selected");
        doReturn(unrelated).when(store.mapper).importProfile(returnedProfile);
        var client = mock(ScentRevMcpClient.class);
        when(client.getFragranceProfile("creed-selected")).thenAnswer(call -> {
            assertOutsideTransaction(transactions);
            return returnedProfile;
        });
        var mapper = transactional(store.mapper, transactions);
        var cache = transactional(new ScentRevOnDemandCacheAccess(store.perfumes, mapper), transactions);
        var service = transactional(new ScentRevOnDemandPerfumeService(client, cache), transactions);
        assertThatThrownBy(() -> service.getOrImportBySlug("creed-selected"))
                .isInstanceOf(ScentRevOnDemandException.class).hasNoCause().satisfies(error ->
                        assertThat(((ScentRevOnDemandException) error).getFailureType())
                                .isEqualTo(ScentRevOnDemandException.FailureType.IDENTIFIER_CONFLICT));
        assertThat(transactions.committed).containsExactly(1); // Initial cache read only.
        assertThat(transactions.rolledBack).containsExactly(2); // Mapper and post-mapper identity guard share this transaction.
        verify(store.mapper).importProfile(returnedProfile);
        verify(client).getFragranceProfile("creed-selected");
        verifyNoMoreInteractions(client);
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
        private final Map<Integer, Boolean> readOnly = new HashMap<>();
        private Integer current;
        private int next;
        @Override protected Object doGetTransaction() { return new Transaction(current); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Transaction) transaction).id != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            current = ++next;
            ((Transaction) transaction).id = current;
            readOnly.put(current, definition.isReadOnly());
        }
        @Override protected Object doSuspend(Object transaction) { Integer suspended = current; current = null; return suspended; }
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
