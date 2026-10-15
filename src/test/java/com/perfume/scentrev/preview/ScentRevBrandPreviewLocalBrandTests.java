package com.perfume.scentrev.preview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.domain.Brand;
import com.perfume.repository.BrandRepository;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.curated.CuratedBrandCatalog;
import com.perfume.scentrev.curated.CuratedBrandDefinition;
import com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType;
import com.perfume.scentrev.curated.CuratedBrandDefinition.SourceEntry;
import com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScentRevBrandPreviewLocalBrandTests {
    private final BrandRepository brands = mock(BrandRepository.class);
    private final PerfumeRepository perfumes = mock(PerfumeRepository.class);
    private final ScentRevCuratedBrandConfiguration configuration = mock(ScentRevCuratedBrandConfiguration.class);
    private final ScentRevBrandPreviewLocalBrand local = new ScentRevBrandPreviewLocalBrand(brands, perfumes, configuration);

    @Test
    void readsTheSeededBrandAndCountOnlyWithNoRepositoryWrites() {
        var brand = seededBrand();
        when(configuration.load()).thenReturn(catalog(definition("creed", true, VerificationStatus.VERIFIED, "Creed")));
        when(brands.findByBrandSlug("creed")).thenReturn(Optional.of(brand));
        when(perfumes.countByBrand_Id(8L)).thenReturn(3_000_000_000L);
        assertThat(local.read("creed")).isEqualTo(new ScentRevBrandPreviewLocalBrand.Snapshot("Creed", "creed", 3_000_000_000L));
        verify(brands).findByBrandSlug("creed"); verifyNoMoreInteractions(brands);
        verify(perfumes).countByBrand_Id(8L); verifyNoMoreInteractions(perfumes);
    }

    @ParameterizedTest
    @CsvSource({"false,VERIFIED", "true,DISABLED", "false,DISABLED", "true,UNRESOLVED"})
    void reusesVerifiedEnabledPolicyAndRejectsExcludedDefinitionsBeforeDbReads(boolean enabled, VerificationStatus status) {
        String slug = status == VerificationStatus.UNRESOLVED ? null : "creed";
        when(configuration.load()).thenReturn(catalog(definition(slug, enabled, status, "Creed")));
        assertThatThrownBy(() -> local.read("creed")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("VERIFIED enabled");
        verifyNoInteractions(brands, perfumes);
    }

    @Test
    void missingOrAmbiguousDefinitionsAndMissingQueryAreRejected() {
        var verified = definition("creed", true, VerificationStatus.VERIFIED, "Creed");
        for (var catalog : List.of(catalog(definition("other", true, VerificationStatus.VERIFIED, "Other")),
                new CuratedBrandCatalog(List.of("A", "B"), List.of(
                        new CuratedBrandDefinition("a", "A", "A", "creed", true, VerificationStatus.VERIFIED,
                                "test evidence", null, null, List.of(new SourceEntry(1, "A", EntryType.BRAND))),
                        new CuratedBrandDefinition("b", "B", "B", "creed", true, VerificationStatus.VERIFIED,
                                "test evidence", null, null, List.of(new SourceEntry(2, "B", EntryType.ALIAS))))),
                catalog(new CuratedBrandDefinition(verified.canonicalBrandKey(), "Creed", null, "creed", true,
                        VerificationStatus.VERIFIED, "evidence", null, null, verified.sources())))) {
            when(configuration.load()).thenReturn(catalog);
            assertThatThrownBy(() -> local.read("creed")).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(brands, perfumes);
    }

    @Test
    void malformedCuratedMappingIsRejectedWithoutProviderResolutionOrDbReads() {
        when(configuration.load()).thenThrow(new IllegalArgumentException("Invalid curated definition"));
        assertThatThrownBy(() -> local.read("creed")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(brands, perfumes);
    }

    @Test
    void missingOrUnpersistedOrMismatchedBrandIsNeverCreatedOrUpdated() {
        when(configuration.load()).thenReturn(catalog(definition("creed", true, VerificationStatus.VERIFIED, "Creed")));
        for (var value : List.of(Optional.<Brand>empty(), Optional.of(new Brand("Creed", "creed")),
                Optional.of(new Brand("Wrong", "other")))) {
            when(brands.findByBrandSlug("creed")).thenReturn(value);
            assertThatThrownBy(() -> local.read("creed")).isInstanceOf(IllegalArgumentException.class);
        }
        verify(brands, times(3)).findByBrandSlug("creed"); verifyNoMoreInteractions(brands);
        verifyNoInteractions(perfumes);
    }

    @Test
    void actualReadOnlyTransactionEndsBeforeTheFirstProviderCall() {
        var transactions = new ReadOnlyTransactions();
        when(configuration.load()).thenReturn(catalog(definition("creed", true, VerificationStatus.VERIFIED, "Creed")));
        when(brands.findByBrandSlug("creed")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return Optional.of(seededBrand());
        });
        when(perfumes.countByBrand_Id(8L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return 12L;
        });
        var client = mock(ScentRevMcpClient.class);
        when(client.searchFragrancesFiltered("creed", 0)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ScentRevBrandPreviewServiceTests.page(0, false);
        });
        var service = transactional(new ScentRevBrandPreviewService(transactional(local, transactions), client), transactions);
        assertThat(service.preview(ScentRevBrandPreviewServiceTests.options(5, 5)).completed()).isTrue();
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isEqualTo(1);
        verify(brands).findByBrandSlug("creed"); verifyNoMoreInteractions(brands);
        verify(perfumes).countByBrand_Id(8L); verifyNoMoreInteractions(perfumes);
        verify(client).searchFragrancesFiltered("creed", 0); verifyNoMoreInteractions(client);
    }

    @Test
    void currentClasspathCatalogKeepsExcludedBrandsUnselectableWithoutApiCalls() {
        var actual = new ScentRevCuratedBrandConfiguration(new ObjectMapper());
        var reader = new ScentRevBrandPreviewLocalBrand(brands, perfumes, actual);
        for (var slug : List.of("mika-lokka", "zara", "unknown-brand")) {
            assertThatThrownBy(() -> reader.read(slug)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(brands, perfumes);
    }

    private static Brand seededBrand() { var brand = new Brand("Creed", "creed"); ReflectionTestUtils.setField(brand, "id", 8L); return brand; }
    private static CuratedBrandCatalog catalog(CuratedBrandDefinition definition) { return new CuratedBrandCatalog(List.of("Creed"), List.of(definition)); }
    private static CuratedBrandDefinition definition(String slug, boolean enabled, VerificationStatus status, String query) {
        return new CuratedBrandDefinition("test", "Creed", query, slug, enabled, status, slug == null ? null : "test evidence",
                null, null, List.of(new SourceEntry(1, "Creed", EntryType.BRAND)));
    }
    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, ReadOnlyTransactions transactions) {
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); interceptor.afterPropertiesSet();
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(interceptor); return (T) proxy.getProxy();
    }
    private static class ReadOnlyTransactions extends AbstractPlatformTransactionManager {
        int begun, committed;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { assertThat(definition.isReadOnly()).isTrue(); begun++; }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
