package com.perfume.scentrev.curated;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus.VERIFIED;
import static com.perfume.scentrev.curated.ScentRevCuratedBrandDbImportServiceTests.catalog;
import static com.perfume.scentrev.curated.ScentRevCuratedBrandDbImportServiceTests.parent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.perfume.domain.Brand;
import com.perfume.repository.BrandRepository;

/** Real Spring transaction advice with a rollback-capable memory store; no JDBC or provider calls. */
class ScentRevCuratedBrandDbImportTransactionTests {
    private final ScentRevCuratedBrandConfiguration configuration = mock(ScentRevCuratedBrandConfiguration.class);
    private final BrandRepository brands = mock(BrandRepository.class);
    private final Map<String, Brand> rows = new LinkedHashMap<>();
    private final RecordingTransactions transactions = new RecordingTransactions(rows);
    private ScentRevCuratedBrandDbImportService importer;
    private long nextId = 100;

    @BeforeEach
    void transactionProxyAndRepositoryStore() {
        when(brands.findByBrandSlug(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.ofNullable(rows.get(call.getArgument(0)));
        });
        when(brands.save(any(Brand.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Brand brand = call.getArgument(0);
            if ("fails".equals(brand.getBrandSlug())) { throw new DataIntegrityViolationException("Fixture persistence failure"); }
            ReflectionTestUtils.setField(brand, "id", nextId++);
            rows.put(brand.getBrandSlug(), brand);
            return brand;
        });
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        var factory = new ProxyFactory(new ScentRevCuratedBrandDbImportService(configuration, brands));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        importer = (ScentRevCuratedBrandDbImportService) factory.getProxy();
    }

    @Test
    void allInsertsAndManagedUpdatesCommitTogetherInOneSpringTransaction() {
        var existing = existingBrand();
        when(configuration.load()).thenReturn(catalog(
                parent("first", "Preferred", "Provider", "existing", true, VERIFIED, 1),
                parent("second", "Second", "Second", "second", true, VERIFIED, 2)));
        var result = importer.importVerifiedBrands();
        assertThat(result).isEqualTo(new ScentRevCuratedBrandDbImportResult(2, 1, 1, 0));
        assertThat(transactions.events).containsExactly("begin", "commit");
        assertThat(rows).hasSize(2);
        assertThat(rows.get("existing")).isSameAs(existing);
        assertThat(existing.getId()).isEqualTo(77L);
        assertThat(existing.getName()).isEqualTo("Preferred");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void laterPersistenceFailureRollsBackEarlierInsertAndNameUpdate() {
        var existing = existingBrand();
        when(configuration.load()).thenReturn(catalog(
                parent("first", "Preferred", "Provider", "existing", true, VERIFIED, 1),
                parent("second", "Second", "Second", "second", true, VERIFIED, 2),
                parent("third", "Third", "Third", "fails", true, VERIFIED, 3)));
        assertThatThrownBy(importer::importVerifiedBrands).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(transactions.events).containsExactly("begin", "rollback");
        assertThat(rows.keySet()).containsExactly("existing");
        assertThat(rows.get("existing")).isSameAs(existing);
        assertThat(existing.getId()).isEqualTo(77L);
        assertThat(existing.getName()).isEqualTo("Original");
        verify(brands, times(2)).save(any(Brand.class)); // One successful fixture insert before the failure.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void duplicateInputRollsBackWithoutAnyRepositoryInteraction() {
        when(configuration.load()).thenReturn(catalog(
                parent("first", "First", "First", "same", true, VERIFIED, 1),
                parent("second", "Second", "Second", "same", true, VERIFIED, 2)));
        assertThatThrownBy(importer::importVerifiedBrands).isInstanceOf(IllegalArgumentException.class);
        assertThat(transactions.events).containsExactly("begin", "rollback");
        assertThat(rows).isEmpty();
        verifyNoInteractions(brands);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private Brand existingBrand() {
        var brand = new Brand("Original", "existing");
        ReflectionTestUtils.setField(brand, "id", 77L);
        rows.put("existing", brand);
        return brand;
    }

    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final Map<String, Brand> rows;
        private final List<String> events = new ArrayList<>();
        private RecordingTransactions(Map<String, Brand> rows) { this.rows = rows; }
        @Override protected Object doGetTransaction() { return new Transaction(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            var state = (Transaction) transaction;
            state.originalRows.putAll(rows);
            rows.forEach((slug, brand) -> state.originalNames.put(slug, brand.getName()));
            events.add("begin");
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            var state = (Transaction) status.getTransaction();
            state.originalRows.forEach((slug, brand) -> brand.updateName(state.originalNames.get(slug)));
            rows.clear();
            rows.putAll(state.originalRows);
            events.add("rollback");
        }
        private static class Transaction {
            private final Map<String, Brand> originalRows = new LinkedHashMap<>();
            private final Map<String, String> originalNames = new LinkedHashMap<>();
        }
    }
}
